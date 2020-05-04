package site.pyyf.commons.event;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import site.pyyf.blog.controller.BaseController;
import site.pyyf.commons.utils.RedisKeyUtil;
import site.pyyf.community.entity.*;
import site.pyyf.commons.utils.CommunityConstant;
import site.pyyf.commons.utils.CommunityUtil;
import site.pyyf.commons.utils.MailClient;
import site.pyyf.community.service.IFollowService;
import site.pyyf.community.service.impl.DiscussPostService;
import site.pyyf.community.service.impl.ElasticsearchService;
import site.pyyf.community.service.impl.MessageService;
import com.qiniu.common.QiniuException;
import com.qiniu.common.Zone;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import site.pyyf.community.entity.*;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

@Component
public class EventConsumer extends BaseController implements CommunityConstant {

    @Autowired
    protected TemplateEngine templateEngine;

    private static final Logger logger = LoggerFactory.getLogger(EventConsumer.class);

    @Autowired
    protected RedisTemplate redisTemplate;

    @Autowired
    private MessageService messageService;

    @Autowired
    private IFollowService iFollowService;

    @Autowired
    private DiscussPostService discussPostService;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Value("${wk.image.command}")
    private String wkImageCommand;

    @Value("${wk.image.storage}")
    private String wkImageStorage;

    @Value("${qiniu.key.access}")
    private String accessKey;

    @Value("${qiniu.key.secret}")
    private String secretKey;

    @Value("${qiniu.bucket.share.name}")
    private String shareBucketName;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    protected MailClient mailClient;


    @KafkaListener(topics = {TOPIC_EMAIL})
    public void handleSendEmail(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        Map<String, Object> emailSetting = event.getData();
        Context context = new Context();
        context.setVariable("username", (String) emailSetting.get("username"));
        context.setVariable("url", (String) emailSetting.get("url"));
        context.setVariable("title", (String) emailSetting.get("title"));
        context.setVariable("content", (String) emailSetting.get("content"));
        String content = null;
        if ((int) emailSetting.get("emailType") == EMAIL_TYPE_BLOG_USER)
            content = templateEngine.process("mail/notifyBlogUser", context);
        else
            content = templateEngine.process("mail/notifyCommentUser", context);
        mailClient.sendMail((String) emailSetting.get("email"), "【鹏圆云方博客】", content);
    }

    @KafkaListener(topics = {TOPIC_COMMENT, TOPIC_LIKE, TOPIC_FOLLOW})
    public void handleCommentMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        // 发送站内通知
        Message message = new Message();
        message.setFromId(SYSTEM_USER_ID);
        message.setToId(event.getEntityUserId());
        message.setConversationId(event.getTopic());
        message.setCreateTime(new Date());

        Map<String, Object> content = new HashMap<>();
        content.put("userId", event.getUserId());
        content.put("entityType", event.getEntityType());
        content.put("entityId", event.getEntityId());

        if (!event.getData().isEmpty()) {
            for (Map.Entry<String, Object> entry : event.getData().entrySet()) {
                content.put(entry.getKey(), entry.getValue());
            }
        }

        message.setContent(JSONObject.toJSONString(content));
        messageService.addMessage(message);

        /* ------------------- 帖子则发feed ----------------- */
        //feed表示userId(名字为userName)发布了（feedType）entityId（entityType类型的）
        if (event.getEntityType() == ENTITY_TYPE_POST) {

            Feed feed = Feed.builder().feedType(topicToFeedType(event.getTopic()))
                    .createTime(new Date())
                    .userId(event.getUserId())
                    .userName(iUserService.queryById(event.getUserId()).getUsername())
                    .entityType(event.getEntityType())
                    .entityId(event.getEntityId()).build();
            iFeedService.addFeed(feed);


            // 获得所有粉丝
            List<Map<String, Object>> followers = iFollowService.findFollowers(feed.getUserId(), 0, Integer.MAX_VALUE);

            // 给所有5天内登陆过的粉丝推事件
            for (Map<String, Object> userAndFollowTime : followers) {
                User user = (User) (userAndFollowTime.get("user"));
                if ((int) ((new Date().getTime() - user.getLoginTime().getTime()) / (1000 * 3600 * 24)) < 5) {
                    //把这个feed扔到redis的timeline中
                    String timelineKey = RedisKeyUtil.getLatestTimelineKey(user.getId());
                    redisTemplate.opsForList().leftPush(timelineKey, feed);
                    // 限制最长长度，如果timelineKey的长度过大，就删除后面的新鲜事
                    while (redisTemplate.opsForList().size(timelineKey) > FEEDTIMELINECOUNT)
                        redisTemplate.opsForList().rightPop(timelineKey);
                }
            }
        }
    }


    @KafkaListener(topics = {TOPIC_VIEW})
    public void handleView(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        /* ------------------- 帖子则发feed ----------------- */
        /* ------------------- 将event的data的viewTags中的数据全部取出来放到redis中 ----------------- */
        if (event.getEntityType() == ENTITY_TYPE_POST) {
            String timelineKey = RedisKeyUtil.getLatestViewTagsKey(event.getUserId());
            //fastjson将数组转化为了jsonarray,所以这里先将jsonarry转化为string,再通过parseArray方法转为list

            List<String> tags = JSONObject.parseArray(JSONObject.toJSONString(event.getData().get("viewTags"), SerializerFeature.WriteClassName), String.class);
            if(tags.size()>0){
                for(String tag:tags){
                    redisTemplate.opsForList().leftPush(timelineKey, tag);
                    // 限制最长长度，如果timelineKey的长度过大，就删除后面的新鲜事
                    while (redisTemplate.opsForList().size(timelineKey) > FEEDTAGCOUNT)
                        redisTemplate.opsForList().rightPop(timelineKey);
                }
            }

        }
    }



    // 消费发帖事件
    @KafkaListener(topics = {TOPIC_PUBLISH})
    public void handlePublishMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        DiscussPost post = discussPostService.findDiscussPostById(event.getEntityId());
        elasticsearchService.saveDiscussPost(post);


        /* ------------------- 帖子则发feed ----------------- */
        //feed表示userId(名字为userName)发布了（feedType）entityId（entityType类型的）
        if (event.getEntityType() == ENTITY_TYPE_POST) {

            Feed feed = Feed.builder().feedType(topicToFeedType(event.getTopic()))
                    .createTime(new Date())
                    .userId(event.getUserId())
                    .userName(iUserService.queryById(event.getUserId()).getUsername())
                    .entityType(event.getEntityType())
                    .entityId(event.getEntityId()).build();
            iFeedService.addFeed(feed);


            // 获得所有粉丝
            List<Map<String, Object>> followers = iFollowService.findFollowers(feed.getUserId(), 0, Integer.MAX_VALUE);

            // 给所有5天内登陆过的粉丝推事件
            for (Map<String, Object> userAndFollowTime : followers) {
                User user = (User) (userAndFollowTime.get("user"));
                if ((int) ((new Date().getTime() - user.getLoginTime().getTime()) / (1000 * 3600 * 24)) < 5) {
                    String timelineKey = RedisKeyUtil.getLatestTimelineKey(user.getId());
                    redisTemplate.opsForList().leftPush(timelineKey, feed);
                    // 限制最长长度，如果timelineKey的长度过大，就删除后面的新鲜事
                    while (redisTemplate.opsForList().size(timelineKey) > FEEDTIMELINECOUNT)
                        redisTemplate.opsForList().rightPop(timelineKey);
                }
            }
        }


    }

    // 消费删帖事件
    @KafkaListener(topics = {TOPIC_DELETE})
    public void handleDeleteMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        elasticsearchService.deleteDiscussPost(event.getEntityId());
    }

    // 消费分享事件
    @KafkaListener(topics = TOPIC_SHARE)
    public void handleShareMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        String htmlUrl = (String) event.getData().get("htmlUrl");
        String fileName = (String) event.getData().get("fileName");
        String suffix = (String) event.getData().get("suffix");

        String cmd = wkImageCommand + " --quality 75 "
                + htmlUrl + " " + wkImageStorage + "/" + fileName + suffix;
        try {
            Runtime.getRuntime().exec(cmd);
            logger.info("生成长图成功: " + cmd);
        } catch (IOException e) {
            logger.error("生成长图失败: " + e.getMessage());
        }

        // 启用定时器,监视该图片,一旦生成了,则上传至七牛云.
        UploadTask task = new UploadTask(fileName, suffix);
        Future future = taskScheduler.scheduleAtFixedRate(task, 500);
        task.setFuture(future);
    }

    class UploadTask implements Runnable {

        // 文件名称
        private String fileName;
        // 文件后缀
        private String suffix;
        // 启动任务的返回值
        private Future future;
        // 开始时间
        private Long startTime;
        // 上传次数
        private int uploadTimes;

        public UploadTask(String fileName, String suffix) {
            this.fileName = fileName;
            this.suffix = suffix;
            this.startTime = System.currentTimeMillis();
        }

        public void setFuture(Future future) {
            this.future = future;
        }

        @Override
        public void run() {
            // 生成失败
            if (System.currentTimeMillis() - startTime > 30000) {
                logger.error("执行时间过长,终止任务:" + fileName);
                future.cancel(true);
                return;
            }
            // 上传失败
            if (uploadTimes >= 3) {
                logger.error("上传次数过多,终止任务:" + fileName);
                future.cancel(true);
                return;
            }

            String path = wkImageStorage + "/" + fileName + suffix;
            File file = new File(path);
            if (file.exists()) {
                logger.info(String.format("开始第%d次上传[%s].", ++uploadTimes, fileName));
                // 设置响应信息
                StringMap policy = new StringMap();
                policy.put("returnBody", CommunityUtil.getJSONString(0));
                // 生成上传凭证
                Auth auth = Auth.create(accessKey, secretKey);
                String uploadToken = auth.uploadToken(shareBucketName, fileName, 3600, policy);
                // 指定上传机房
                UploadManager manager = new UploadManager(new Configuration(Zone.zone1()));
                try {
                    // 开始上传图片
                    Response response = manager.put(
                            path, fileName, uploadToken, null, "image/" + suffix, false);
                    // 处理响应结果
                    JSONObject json = JSONObject.parseObject(response.bodyString());
                    if (json == null || json.get("code") == null || !json.get("code").toString().equals("0")) {
                        logger.info(String.format("第%d次上传失败[%s].", uploadTimes, fileName));
                    } else {
                        logger.info(String.format("第%d次上传成功[%s].", uploadTimes, fileName));
                        future.cancel(true);
                    }
                } catch (QiniuException e) {
                    logger.info(String.format("第%d次上传失败[%s].", uploadTimes, fileName));
                }
            } else {
                logger.info("等待图片生成[" + fileName + "].");
            }
        }
    }

    private static int topicToFeedType(String topic) {
        if (topic.equals(TOPIC_LIKE))
            return FEED_LIKE;
        else if (topic.equals(TOPIC_COMMENT))
            return FEED_COMMENT;
        else if (topic.equals(TOPIC_PUBLISH))
            return FEED_PUBLISH;
        else
            throw new RuntimeException("无效的类别参数");
    }


}
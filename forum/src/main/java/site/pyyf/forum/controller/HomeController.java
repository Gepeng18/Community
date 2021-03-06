package site.pyyf.forum.controller;

import site.pyyf.forum.entity.*;
import site.pyyf.commons.utils.CommunityConstant;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.*;
import java.util.stream.Collectors;

@Controller
public class HomeController extends CommunityBaseController implements CommunityConstant {


    @RequestMapping(path = "/", method = RequestMethod.GET)
    public String root() {
        return "forward:index";
    }

    /**
     * @param model
     * @param page
     * @param orderMode 查询顺序： 0-常规时间顺序；1-热度
     * @param tag
     * @return
     */
    @RequestMapping(path = "/index", method = RequestMethod.GET)
    public String getIndexPage(Model model, Page page,
                               @RequestParam(name = "orderMode", defaultValue = "0") int orderMode,
                               @RequestParam(name = "tag", defaultValue = "-1") String tag) {
        // 方法调用前,SpringMVC会自动实例化Model和Page,并将Page注入Model.
        // 所以,在thymeleaf中可以直接访问Page对象中的数据.
        DiscussPost query = DiscussPost.builder().userId(-1).build();
        if ("-1".equals(tag)) {
            //没有tag，将-1作为参数构成query
            query.setTags(tag);
        }else {
            //传入了tag，将其作为参数构成query
            tag = tag.replace("+", "").replace("*", "").replace("?", "");
            query.setTags(tag);
        }

        page.setRows(iDiscussPostService.queryCount(query));
        page.setPath("/index?orderMode=" + orderMode+"&tag="+tag);

        List<Feed> feeds = null;
        Set<Feed> published = null;
        Set<Feed> liked = null;
        Set<Feed> commented = null;
        if(hostHolder.getUser()!=null){
            feeds = iFeedService.getFeeds();
            published = feeds.stream().filter(feed -> {
                return feed.getFeedType() == FEED_PUBLISH;
            }).collect(Collectors.toSet());
            liked = feeds.stream().filter(feed -> {
                return feed.getFeedType() == FEED_LIKE;
            }).collect(Collectors.toSet());
            commented = feeds.stream().filter(feed -> {
                return feed.getFeedType() == FEED_COMMENT;
            }).collect(Collectors.toSet());
        }

        List<DiscussPost> discussPosts = iDiscussPostService
                .queryAllByLimit(query,orderMode, page.getOffset(), page.getLimit());
        List<Map<String, Object>> discussPostVOS = new ArrayList<>();
        if (discussPosts .size()!= 0) {
            for (DiscussPost post : discussPosts) {
                Map<String, Object> discussPostVO = new HashMap<>();
                if(hostHolder.getUser()!=null) {
                    String feedContent = iFeedService.getFeedContentByPostId(post.getId(), published, liked, commented);
                    if (feedContent.length() > 0) {
                        discussPostVO.put("feedContent", feedContent);
                    } else {
                        //简单粗暴，这样前端就好写了
                        discussPostVO.put("feedContent", "");
                    }
                }
                discussPostVO.put("post", post);
                User user = iUserService.queryById(post.getUserId());
                discussPostVO.put("user", user);

                Long likeCount = iLikeService.findEntityLikeCount(ENTITY_TYPE_POST, post.getId());
                discussPostVO.put("likeCount", (long) likeCount);

                discussPostVOS.add(discussPostVO);
            }
        }
        model.addAttribute("discussPosts", discussPostVOS);
        model.addAttribute("orderMode", orderMode);

        List<Tag> hotTags = tagCache.getShowTags();
        model.addAttribute("hotTags", hotTags);

        /* ------------------- 热门问题 ----------------- */
        List<DiscussPost> hotPosts = iDiscussPostService
                .queryAllByLimit(DiscussPost.builder().userId(-1).tags("-1").build(),3, 0, 5);
        model.addAttribute("hotPosts", hotPosts);

        /* ------------------- 如果是标签查询则需要进行回显 ----------------- */
        if(!tag.equals("-1")) {
            model.addAttribute("tag", tag);
        }
        return "index";
    }

    @RequestMapping(path = "/error", method = RequestMethod.GET)
    public String getErrorPage() {
        return "error/500";
    }

    @RequestMapping(path = "/denied", method = RequestMethod.GET)
    public String getDeniedPage(Model model) {
        model.addAttribute("reason","权限不足");
        return "error/404";
    }


}

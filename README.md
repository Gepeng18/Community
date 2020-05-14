# Community

### 技术栈
****




### 功能梳理

#### 论坛模块
##### 普通用户权限：
- 支持站内账号登录，QQ登录和Github账号登录（一键登录，方便快捷）
- 支持发布帖子，可以添加自定义标签，支持markdown格式，同时编辑页面提供目录索引
- 支持用户编辑自己发布帖子
- 支持点赞帖子，评论帖子，对评论进行评论，对评论进行点赞，当用户的帖子或者评论被点赞或评论时，会通过站内信通知用户
- 查看帖子详情页面时，可以同时看到论坛的热门帖，以及相关的帖子（通过标签匹配获得）
- 支持查看个人主页，查看该用户发布的所有帖子，以及最新的动态
- 支持站内发送私信
- 支持站内搜索，提供多种搜索条件
    - "user:Gepeng18"    搜索Gepeng18发布的帖子
    - "关键词 in:title"   搜索题目中有"关键词"的帖子
    - "关键词 in:content"  搜索帖子内容中有"关键词"的帖子
    - "关键词 in:tag"    搜索标签中有"关键词"的帖子
    - 支持以上条件组合使用
    
##### 管理员权限：
- 可以删除或者编辑用户的帖子

##### 版主权限：
- 可以将帖子加精或者置顶


#### 云盘模块
##### 普通用户权限：
- 下载所有的文件
- 在线播放低于N兆的音乐，视频
- 在线查看代码文件
- 在线浏览图片
- 支持获取下载链接，提供给他人下载
- 在线阅读电子书
- 在线阅读工程

##### 管理员专有权限：
- 上传文件
- 删除文件或文件夹
- 可以查看"私人文件夹",该文件夹在普通用户模块不显示
- 创建文件夹
- 分享文件或者文件夹
- 转存其他管理员分享的文件或文件夹

****

### 模块梳理
#### 注册、登录模块
##### 注册
1. 检查注册环境是否正常。当用户点击页面顶部“登录”按钮，打开注册页面，允许注册时，输入账户、密码和邮箱，通过表单提交注册数据。
2. 服务端对用户输入的信息进行检测。填入的信息不能为空，验证输入的账户是否已经存在，填写的邮箱是否已经注册过。
3. 注册用户。用户传入的密码通过 MD5+salt 的方式加密，设置用户的类型为普通用户，用户的状态为未激活状态，设置用户的激活码，给用户随机分配系统自带的头像，
   生成用户注册的时间。
4. 激活注册账号。服务端利用模板引擎发送激活邮件至用户注册时填写的邮箱中。
   用户点击邮件中的激活码，访问服务端的激活服务。通过验证激活码来验证激活是否成功。
5. 利用kaptcha生成验证码，将验证码放入cookie中，并存入Redis里。将验证码图片输出给浏览器。

##### 登录
1. 登录方式分为论坛注册账户登录和第三方账号登录（github和qq）。
2. 论坛内注册的账户登录：提交账户信息后，服务端会先验证用户填入的信息，其中验证码在表现层就要判断，验证码不对密码和账号就不要判断了；
   登录信息无误后，生成登录凭证（loginTicket），将凭证发送给客户端。浏览器每次访问时通过cookie判断登录状态。
3. 第三方登录：点击登录页下方的第三方登录后，获取到了用户信息，开始判断是否已经注册。注册过即取出，否则注册。生成登录凭证。

#### 缓存


#### 个性化推荐模块

1. 整体上本模块采用"推"和"拉"的方式并存。
2. 当用户点赞，评论，发布博客时，会将这些"动态"封装成feed对象存入数据库，同时将这个动态发给所有的粉丝（从数据库中找到所有的粉丝，筛选最近n天登陆过的人，然后将此feed存入他们对应的redis的timelineKey中，即
每个用户的redis的timelineKey中存放着自己关注的对象的动态）
3. 当用户登录后，redis中有值则从redis中对应的key中取，否则先从redis中获取所有的关注的人，然后从数据库中遍历搜索。 至此，获得了用户关注的人的最近动态
4. 当用户每查看一个帖子时，就会将这个帖子的标签存到redis对应的tagKey中。
5. 最终，用户的个性化页面展示顺序即为：
    1. 用户关注的人发送的帖子
    2. 用户关注的人点赞的帖子
    3. 用户关注的人评论的帖子
    4. 用户最近查看的标签对应的帖子且发布时间是近期(否则用户看了两个"多线程"的帖子，然后向此用户推荐了网站所有的多线程，显然不合适，所以只推荐最近发布的"多线程"的帖子)
    5. 再按照分数递减的顺序
注意点
1. 我们为timelineKey设置了生存时间，如小葛关注了小芳，则小芳发布的帖子，进入了小葛的timelineKey，此时，如果小葛5天没登录了，则从此小芳的点赞评论都不会在告诉小葛，
   此时我们就可以把小葛的timelineKey删除了，当小葛再次登录时，小芳发布的帖子，评论点赞才会进入小葛的timelineKey，以此解决redis内存。
2. 如果我们最近查看了"多线程"，那么分页查询第一页一定是0-10，这时候我们看的帖子的标签就会被redis记录，此时如果我们点击第二页，则从redis中的tagKey取值时，第二页的查询条件就会改变，
   这时候可能第一页查看过的数据，第二页又会出现，或者是有些数据，永远看不到，即我们需要保证分页查询时，每次查询条件不变，所以我们设置了两个key,持久化key（persistence）和最近的key(latest),
   这时候我们每次查询都从持久化key中取，而每次查询第一页时，将最新的key置换为持久化key，这样即可以保证每次查询条件相同，且是伪最新的标签。

#### 主页热门标签显示模块
1. 用户发布帖子的时候，都会附带一个tag标签
2. 使用quartz，每隔3个小时统计一次。遍历所有标签，然后根据每个标签名搜出对应的帖子数量，并统计帖子总分，组成一个tag。这里需要注意，我们在查询某标签（如算法）对应的帖子时，
   查询数据库的算法使用了“%XX%”（如%算法%）的匹配条件，这会得到其他相关联的标签（如“算法总结”等）。
3. 使用treeSet对这些标签进行排序，然后选出N个帖子存储在tagCache中，每当用户访问主页面时，就从TagCache中去取
4. 当帖子数量增多时，统计时间也可以设置为每天晚上3点，夜深人静偷偷统计。（统计帖子的个数，有点误差可以接受，所以一天统计一次似乎也可行）



****




### 环境搭建
- 环境搭建，见[docker.md](https://github.com/Freya19/Community/blob/master/docs/docker.md)，本项目所有服务都在docker环境下搭建。
- 数据库初始化：所有的SQL script见[SQL_Script](https://github.com/Freya19/Community/tree/master/docs/SQL_Script)
  <br/>运行顺序为 	init_schema.sql(建表） -> init_quartz.sql（quartz相关表）
****




### 更新日志
- 见[bugs.md](https://github.com/Freya19/Community/blob/master/docs/更新日志.md)
****



### 项目截图

#### 首页
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/首页.png" alt="img" width="67%;" />

#### 个性化推荐
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/个性化预览.png" alt="img" width="67%;" />

#### 帖子详情页
- 支持目录查看即目录跳转
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/帖子详情页.png" alt="img" width="67%;" />

#### 站内搜索
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/站内搜索.png" alt="img" width="67%;" />

#### 标签检索
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/标签检索.png" alt="img" width="67%;" />

#### 个人主页
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/个人主页.png" alt="img" width="67%;" />

#### 最新动态
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/最新动态.png" alt="img" width="67%;" />

#### 站内私信功能
<img src="http://pyyf.oss-cn-hangzhou.aliyuncs.com/post/img/2020/04/09/22/49/57/img/站内私信功能.png" alt="img" width="67%;" />



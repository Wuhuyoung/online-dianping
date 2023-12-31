## 在线点评

### 项目介绍

仿大众点评项目

<img src="https://typora-1314662469.cos.ap-shanghai.myqcloud.com/img/202306231923505.png" alt="image-20230623192316059" style="zoom:50%;" /><img src="https://typora-1314662469.cos.ap-shanghai.myqcloud.com/img/202306231924290.png" alt="image-20230623192414134" style="zoom:50%;" /><img src="https://typora-1314662469.cos.ap-shanghai.myqcloud.com/img/202306231925871.png" alt="image-20230623192502849" style="zoom:50%;" />

实现功能：

1. 短信登录

   使用 Redis 共享 session 来实现。

2. 商户查询缓存

   使用 旁路缓存 实现缓存与数据库的双写一致，使用 缓存空对象 解决缓存穿透，采用 互斥锁 / 逻辑过期 来解决缓存击穿。

3. 优惠卷秒杀

   使用 Redis 的计数器功能， 结合 Lua 完成高性能的 Redis 操作，来解决库存超卖问题，同时使用到了 Redis 分布式锁来解决集群模式下的一人一单问题，最后采用 Redisson 作为分布式锁。

4. 附近的商户

   利用 Redis 的 GEOHash 来完成对于地理坐标的操作

5. UV统计

   利用 Redis HyperLogLog 的统计功能

6. 用户签到

   使用 BitMap 数据统计功能

7. 好友关注

   基于 Set 集合的关注、取关、共同关注、消息推送

8. 达人探店

   基于 Redis List 的点赞功能，点赞排行榜采用 Redis SortedSet 实现

### 如何使用该项目

#### 导入后端项目

将 sql 文件夹中的表和数据导入数据库，修改 MySQL 和 Redis 配置

启动项目，在浏览器访问：http://localhost:8081/shop-type/list，如果可以看到数据证明运行正常。

#### 导入前端项目

资料中提供了一个 nginx-1.18.0文件夹，将其复制到任意目录，要确保该目录不包含中文、特殊字符和空格

在 nginx 所在目录下打开一个 CMD 窗口，输入命令

```sh
start nginx.exe
```

在浏览器中访问 http://127.0.0.1:8080，按 F12，打开手机模式，即可看到页面

#### 登录

手机号：13686869696（也可以使用其他手机号，后台会创建新账号）

验证码：此处为模拟发送验证码，生成的验证码会在后端控制台打印

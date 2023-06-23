package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;
import static com.hmdp.utils.SystemConstants.MAX_PAGE_SIZE;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private IUserService userService;

    @Resource
    private FollowMapper followMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlog(Long id) {
        //查询笔记
        Blog blog = getById(id);
        //查询发布笔记的用户
        queryBlogUser(blog);
        //查询该用户是否点赞了该笔记
        queryBlogIsLiked(blog);
        //返回
        return Result.ok(blog);
    }

    private void queryBlogIsLiked(Blog blog) {
        //1、获取用户信息
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            //用户未登录
            return;
        }
        Long userId = user.getId();
        //2、判断用户是否已经点赞(redis)
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3、给blog的isLiked字段赋值
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            //查询blog有关的用户
            this.queryBlogUser(blog);
            //查询blog是否被点赞
            this.queryBlogIsLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 修改用户点赞信息
     * @param id
     * @return
     */
    @Override
    public Result updateBlogLiked(Long id) {
        //1、获取用户信息
        Long userId = UserHolder.getUser().getId();
        //2、判断用户是否已经点赞(redis)
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            //3、如果未点赞，可以点赞
            //3.1、数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2、保存用户到redis的set集合
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //4、如果已经点赞过，即为取消点赞
            //4.1、数据点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2、从redis的set集合中移除用户
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 5);
        if(top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idsStr = StrUtil.join(",", ids);
        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("order by field(id," + idsStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.转化为UserDTO返回
        return Result.ok(userDTOS);
    }

    /**
     * 根据用户id查询用户的博客
     * @param currentPage
     * @param userId
     * @return
     */
    @Override
    public List<Blog> listBlogOfUser(Integer currentPage, Long userId) {
        LambdaQueryWrapper<Blog> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Blog::getUserId, userId);
        Page<Blog> page = new Page<>(currentPage, MAX_PAGE_SIZE);
        List<Blog> records = blogMapper.selectPage(page, lqw).getRecords();
        return records;
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess) {
            return Result.fail("保存笔记失败");
        }
        //3.查询关注我的用户id
        LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Follow::getFollowUserId, user.getId());
        List<Follow> follows = followMapper.selectList(lqw);
        //4.保存笔记到粉丝的sorted_set中，score为时间戳
        long currentTime = System.currentTimeMillis();
        for (Follow follow : follows) {
            //4.1 获取用户id
            String key = FOLLOW_KEY + follow.getUserId();
            //4.2 推送
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), currentTime);
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取用户
        Long userId = UserHolder.getUser().getId();
        //2.从sorted_set中获取 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FOLLOW_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if(CollectionUtils.isEmpty(typedTuples)) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int lastOffSet = 1;
        //4.解析数据
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //4.1 获取笔记id
            ids.add(Long.valueOf(tuple.getValue()));
            //4.2 获取分数(时间戳)
            long time = tuple.getScore().longValue();
            if(time == minTime) {
                lastOffSet++;
            } else {
                minTime = time;
                lastOffSet = 1;
            }
        }
        //4.3 查询笔记
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("order by field(id, " + idStr + ")").list();
        blogs.forEach(blog -> {
            //查询blog有关的用户
            this.queryBlogUser(blog);
            //查询blog是否被点赞
            this.queryBlogIsLiked(blog);
        });
        //5.封装返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(lastOffSet);
        return Result.ok(result);
    }
}

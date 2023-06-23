package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private FollowMapper followMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //2.判断是否要关注
        if(BooleanUtil.isTrue(isFollow)) {
            //3.新增关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess) {
                //需要将关注的用户id存入redis的set集合中，方便后续求共同关注
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //4.取关
            LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<>();
            lqw.eq(followUserId != null, Follow::getFollowUserId, followUserId)
                    .eq(Follow::getUserId, userId);
            boolean isSuccess = remove(lqw);
            if(isSuccess) {
                //将取关的id从redis set集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1.查询用户id
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<>();
        lqw.eq(followUserId != null, Follow::getFollowUserId, followUserId)
                .eq(Follow::getUserId, userId);
        Long count = followMapper.selectCount(lqw);
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //1.获取用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //2.求共同关注的用户id交集
        String key2 = "follow:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect == null || intersect.isEmpty()) {
            //没有共同关注
            return Result.ok(Collections.emptyList());
        }
        //3.解析id集合，转为Long
        List<Long> list = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户
        List<UserDTO> userDTOList = userMapper.selectBatchIds(list).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}

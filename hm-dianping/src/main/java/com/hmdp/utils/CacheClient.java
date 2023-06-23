package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * Redis工具类
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object data, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data), time, unit);
    }

    public void setWithLogicalExpire(String key, Object data, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(data);
        //写入redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存数据 (防止缓存穿透)
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1、根据id查询redis
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、如果存在，直接返回给前端
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是空值
        if(json != null) {
            //说明此时查到的是空字符串，直接返回不用查数据库,防止缓存穿透
            return null;
        }
        // 3、未命中，查询数据库
        R r = dbFallback.apply(id);

        //4、如果没有查到信息，返回错误信息
        if(r == null) {
            //如果没有查到店铺信息，也存入redis中，只不过存的是空的数据(防缓存穿透)
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5、将店铺信息存入redis中
        set(key, r, time, unit);

        //7、返回
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, String lockKeyPrefix,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1、根据id查询redis
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、如果缓存中没有，直接返回
        if(StrUtil.isBlank(json)) {
            return null;
        }

        //3、如果缓存中有，先将json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        //4、判断是否逻辑过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //4.1 未过期，直接返回
            return r;
        }
        //4.2 逻辑过期，尝试获取锁
        boolean isLock = tryLock(lockKeyPrefix + id);
        //4.3 获取锁失败，直接返回旧数据
        if(!isLock) {
            return r;
        }
        //4.4 获取锁成功，开启一个线程查询数据库，重置逻辑过期时间，更新缓存
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                //查询数据库
                R r1 = dbFallback.apply(id);
                //存入redis
                setWithLogicalExpire(key, r1, time, unit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lockKeyPrefix + id);
            }
        });

        //7、返回过期的数据
        return r;
    }

    private boolean tryLock(String key) {
        //当该key存在，就不能添加，可以作为互斥锁 (同时10秒还未释放锁就过期)
        Boolean ifLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ifLock); //防止拆箱时null报空指针异常
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

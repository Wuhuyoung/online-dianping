package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 *  服务实现类
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 利用互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    public Result queryShopByIdWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1、根据id查询redis
        String shopJSON = stringRedisTemplate.opsForValue().get(key);

        //2、如果店铺存在，直接返回给前端
        if(StrUtil.isNotBlank(shopJSON)) {
            return Result.ok(JSONUtil.toBean(shopJSON, Shop.class));
        }
        if(shopJSON != null) {
            //说明此时查到的是空字符串，直接返回不用查数据库,防止缓存穿透
            return Result.fail("店铺信息不存在");
        }
        //3、实现缓存重构，防止缓存击穿
        //3.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            //3.2 判断是否获取成功
            if(!isLock) {
                //3.3 如果获取失败，休眠等待，再次尝试查询缓存
                Thread.sleep(50);
                return queryShopById(id);
            }

            //3.4 如果获取锁成功，查询数据库
            shop = getById(id);

            //4、如果没有查到信息，返回错误信息
            if(shop == null) {
                //如果没有查到店铺信息，也存入redis中，只不过存的是空的数据(防缓存穿透)
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            //5、将店铺信息存入redis中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //6、释放锁
            unlock(lockKey);
        }
        //7、返回
        return Result.ok(shop);
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    /**
     * 利用逻辑过期解决缓存击穿问题
     * @param id
     * @return
     */
    public Result queryShopById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1、根据id查询redis
        String shopJSON = stringRedisTemplate.opsForValue().get(key);

        //2、如果缓存中没有，直接返回
        if(StrUtil.isBlank(shopJSON)) {
            return Result.fail("店铺不存在");
        }

        //3、如果缓存中有，先将json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        //4、判断是否逻辑过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //4.1 未过期，直接返回
            return Result.ok(shop);
        }
        //4.2 逻辑过期，尝试获取锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        //4.3 获取锁失败，直接返回旧数据
        if(!isLock) {
            return Result.ok(shop);
        }
        //4.4 获取锁成功，开启一个线程查询数据库，重置逻辑过期时间，更新缓存
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                saveShopToRedis(id, 10l);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(LOCK_SHOP_KEY + id);
            }
        });

        //7、返回
        return Result.ok(shop);
    }

    /**
     * 尝试获取锁,用 redis中 setIfAbsent方法来模拟互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        //当该key存在，就不能添加，可以作为互斥锁 (同时10秒还未释放锁就过期)
        Boolean ifLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ifLock); //防止拆箱时null报空指针异常
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 缓存预热，将店铺数据放入缓存中
     * @param id
     */
    public void saveShopToRedis(Long id, Long expireTime) {
        //1、查询数据库
        Shop shop = getById(id);
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        //3、加入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId() == null) {
            return Result.fail("店铺不存在");
        }
        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByTypeAndLocation(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要分页
        if(x == null || y == null) {
            //不需要分页，直接查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        //2.获取分页参数 from end
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = from + SystemConstants.DEFAULT_PAGE_SIZE;

        //3.从redis中查询，根据地理位置排序、分页 结果：距离、shopId
        // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000), //寻找5000米内的店铺
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().limit(end).includeDistance());

        //4.解析出id和距离
        if(results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if(content.size() <= from) {
            //没有下一页了，返回
            return Result.ok(Collections.emptyList());
        }

        //4.1 截取 from~end 的部分, 使用stream流中的skip()来跳过前面n个元素
        List<Long> ids = new ArrayList<>(); //shopId
        Map<Long, Double> distanceMap = new HashMap<>(); //distance
        content.stream().skip(from).forEach(con -> {
            //4.2 取出shopId
            String shopId = con.getContent().getName();
            ids.add(Long.valueOf(shopId));
            //4.3 取出距离
            distanceMap.put(Long.valueOf(shopId), con.getDistance().getValue());
        });

        //5.根据id查询店铺
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id, " + idStr + ")").list();
        shops.forEach(shop -> shop.setDistance(distanceMap.get(shop.getId())));
        //6.返回
        return Result.ok(shops);
    }
}

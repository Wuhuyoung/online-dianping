package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoLocation;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
class TestShop {
    @Autowired
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveShop2Redis() {
        shopService.saveShopToRedis(1l, 30l);
    }

    /**
     * 存储店铺数据到 redis中
     */
    @Test
    void loadShopData() {
        //1.获取商铺信息
        List<Shop> list = shopService.list();
        //2.按照店铺类型typeId分组
        //使用map存储，键为typeId，值为店铺List
        /* HashMap<Long, List<Shop>> map = new HashMap<>();
        list.stream().forEach(shop -> {
            //该类型不存在
            if(!map.containsKey(shop.getTypeId())) {
                map.put(shop.getTypeId(), new ArrayList<Shop>());
            }
            List<Shop> shops = map.get(shop.getTypeId());
            shops.add(shop);
            map.put(shop.getTypeId(), shops);
        }); */
        //用stream流来分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        //3.保存到redis的GEO数据结构中
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1 获取商品分类id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            //3.2 获取店铺集合
            List<Shop> shops = entry.getValue();
            //3.3 将店铺全部存入locations中，之后一次性导入redis中(也可以一个一个add) GEOADD key 经度 纬度 member
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                //stringRedisTemplate.opsForGeo() //也可以一个一个add
                // .add(key, new Point(shop.getX(), shop.getY()), shop.getTypeId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

}

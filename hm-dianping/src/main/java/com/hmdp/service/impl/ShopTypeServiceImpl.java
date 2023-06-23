package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 *  服务实现类
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private ShopTypeMapper shopTypeMapper;

    /**
     * 查询商铺类型
     * @return
     */
    @Override
    public Result getTypeList() {
        //1、从redis中查询类型列表
        String key = CACHE_SHOP_TYPE_KEY;
        String shopTypeJSON = stringRedisTemplate.opsForValue().get(key);

        //2、如果查询到，直接返回
        if(StrUtil.isNotBlank(shopTypeJSON)) {
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJSON, ShopType.class);
            return Result.ok(shopTypes);
        }
        //3、如果没有查到，从数据库中查询
        List<ShopType> shopTypeList = shopTypeMapper.getList();

        //4、数据库中如果没有查到，返回错误信息
        if(shopTypeList == null) {
            return Result.fail("店铺列表信息不存在");
        }
        //5、将类型信息存入redis中,并设置缓存时间
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(shopTypeList), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6、返回数据给前端
        return Result.ok(shopTypeList);
    }
}

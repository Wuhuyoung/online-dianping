package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {

    Result queryShopById(Long id);

    Result update(Shop shop);

    Result queryShopByTypeAndLocation(Integer typeId, Integer current, Double x, Double y);
}

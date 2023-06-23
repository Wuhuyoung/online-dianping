package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * 服务类
 */
public interface IUserService extends IService<User> {
    Result sendCode(String phone, HttpSession session);

    User getById(Long userId);

    Result login(LoginFormDTO loginForm, HttpSession session);

    UserDTO queryById(Long id);

    Result sign();

    Result signCount();

}

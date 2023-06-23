package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * 登录拦截器
 */
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、从请求头中获取 token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) {
            return true;
        }

        // 2、基于token 从 redis中获取用户
        Map<Object, Object> map = stringRedisTemplate.opsForHash()
                .entries(LOGIN_USER_KEY + token);
        //　3、判断是否登录
        if(map.isEmpty()) {
            return true;
        }
        // 4、需要将查询到的hashMap转换为 UserDTO对象  false：不忽视错误
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);

        //5、将用户信息存入threadLocal中
        UserHolder.saveUser(userDTO);

        // 6、刷新 token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        // 7、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //此方法在线程销毁后执行，移除user,避免内存泄露
        UserHolder.removeUser();
    }
}

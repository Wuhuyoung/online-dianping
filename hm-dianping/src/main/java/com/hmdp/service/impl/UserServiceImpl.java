package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 服务实现类
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    /**
     * 发送短信验证码
     */
    public Result sendCode(String phone, HttpSession session) {
        //1、校验手机号是否正确
        if(RegexUtils.isPhoneInvalid(phone)) {
            //2、如果手机号无效，返回错误信息
            return Result.fail("手机号无效");
        }
        //3、生成手机验证码
        String code = RandomUtil.randomNumbers(6);

        //4、将验证码存入redis中，并设置有效期2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5、发送验证码
        log.info("发送手机验证码：" + code);

        return Result.ok();
    }

    @Override
    public User getById(Long userId) {
        return userMapper.selectById(userId);
    }

    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、判断手机号和验证码是否正确
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            //2.1、如果手机号无效，返回错误信息
            return Result.fail("手机号错误");
        }
        // 2.2 从redis中取出code
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if(code == null || !code.equals(loginForm.getCode())) {
            //2.3 如果验证码不一致，返回错误信息
            return Result.fail("验证码有误");
        }
        //移除手机验证码
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        //3、用手机号查询user
        User user = userMapper.selectByPhone(phone);

        //4、如果未查询到user，则创建用户
        if(user == null) {
            user = createUserWithPhone(phone);
        }
        // 5、将user信息保存到 redis中
        // 5.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();

        // 5.2 将userDto转化为hashMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().ignoreNullValue()
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // 5.3 将map存入redis中
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // 5.4 设置redis的有效期
        stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);

        // 5.5 返回token给客户端
        return Result.ok(token);
    }

    @Override
    public UserDTO queryById(Long id) {
        User user = getById(id);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return userDTO;
    }

    @Override
    public Result sign() {
        //1.获取用户id
        Long userId = UserHolder.getUser().getId();
        //2.获取当前时间
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + format;
        //3.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //4.存入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取用户id
        Long userId = UserHolder.getUser().getId();
        //2.获取当前时间
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + format;
        //3.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //4.获取用户本月签到的记录，返回的是十进制数 bitfield sign:5:202302 get u20 0 //今天是20号，所以从0开始获取前20位
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(20)).valueAt(0));
        if(CollectionUtils.isEmpty(result)) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0) {
            return Result.ok(0);
        }

        //5.将该数与1进行与运算，得到数字的最后一个bit位
        int count = 0;
        while((num & 1) != 0) {
            //说明已经签到，签到次数+1
            count++;
            //需要将该数右移一位，继续判断前一天是否签到
            num >>>= 1; //>>>是无符号右移
        }

        //6.返回连续签到次数
        return Result.ok(count);
    }

    /**
     * 创建用户
     * @param phone
     * @return
     */
    public User createUserWithPhone(String phone) {
        //1、创建用户
        User user = new User();
        user.setPhone(phone);
        //自动为用户设置一个昵称，格式为"user_" + 10位随机字符
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2、保存用户
        userMapper.insert(user);
        return user;
    }
}

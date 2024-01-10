
package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.query;
import static com.hmdp.utils.RedisConstants.*;

/**
 * @auth: luohoa
 * @description:
 * @create: 2023-11-22-11-39
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper,User> implements IUserService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result login_l(LoginFormDTO loginForm, HttpSession session) {
        // 1、校验验证码
        String code1 = loginForm.getCode();
        // 1.1、从redis中获取验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY);
        // 1.2、校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone))
        {
            return Result.fail("手机号格式错误");
        }
        // 1.3、校验验证码
        if(!code1.equals(code))
        // 2、不一致
        {
            Result.fail("验证码错误！");
        }
        // 3、根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        if (user == null)
        // 4、不存在，创建用户
        {
            //User user1 = new User();
            //user1.setPhone(phone);
            user = createUserWithPhone(phone);

        }
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        // 5、保存到redis中
        // 5.1、生成唯一key
        String token = UUID.randomUUID().toString(true);
        UserHolder.setRandom(token);
        // 5.2、将userDTO转为map结构
        // tip:stringredisTemplate要求系列化过程中要求都是string类型，id是Long
        // Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 5.3、存储,key组成：LOGIN_USER_KEY+token
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        // 5、4、设置过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY,30, TimeUnit.MINUTES);
        // 5.5、返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_"+ RandomUtil.randomString(10));
        save(user);
        return user;
    }
}


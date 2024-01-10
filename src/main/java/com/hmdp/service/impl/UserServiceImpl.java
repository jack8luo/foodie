
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.query;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

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

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_"+ RandomUtil.randomString(10));
        save(user);
        return user;
    }
}


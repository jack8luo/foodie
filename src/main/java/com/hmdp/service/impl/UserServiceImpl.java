
package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.query;

/**
 * @auth: luohoa
 * @description:
 * @create: 2023-11-22-11-39
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper,User> implements IUserService {
    @Override
    public Result login_l(LoginFormDTO loginForm, HttpSession session) {
        // 1、校验验证码
        String code1 = loginForm.getCode();
        String  code = (String) session.getAttribute("code");
        // 1.1、校验手机号
        String phone = loginForm.getPhone();
        if(!RegexUtils.isPhoneInvalid(phone))
        {
            Result.fail("手机号格式错误");
        }
        if(!code1.equals(code))
        // 2、不一致
        {
            Result.fail("验证码错误！");
        }
        // 3、根据手机号查询用户
        User user = query().eq("phone", phone).one();
        if (user == null)
        // 4、不存在，创建用户
        {
            User user1 = new User();
            user1.setPhone(phone);

        }
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        // 5、保存到session中
        session.setAttribute("user", userDTO);
        return Result.ok();
    }
}


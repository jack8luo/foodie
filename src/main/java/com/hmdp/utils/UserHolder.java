package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
    //生成存储String类型的变量和方法
    private static final ThreadLocal<String> tl2 = new ThreadLocal<>();
    //存储String类型的方法
    public static void setRandom(String random) {
        tl2.set(random);
    }
    //获取String类型的方法
    public static String getRandom() {
        return tl2.get();
    }

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }



}

package com.hmdp.utils;

import cn.hutool.core.date.LocalDateTimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * @auth: luohoa
 * @description: Redis实现全局唯一ID
 * @create: 2023-11-24-09-37
 */
@Component
public class RedisIdWorker {
    // 2022年1月1日时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    // 唯一ID自增位数
    private static final int INC_LENGTH = 32;
    private StringRedisTemplate stringRedisTemplate;
    private RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public long nextId(String keyPrefix){
    //     1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long time = epochSecond - BEGIN_TIMESTAMP;
        //     2、生成自增id
        String date = now.format(DateTimeFormatter.ofPattern("yy:MM:dd"));
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //     3、拼接返回
        return time << INC_LENGTH | increment;

    }

    public static void main(String[] args) {
        LocalDateTime of = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long epochSecond = of.toEpochSecond(ZoneOffset.UTC);
        System.out.println(epochSecond);

    }
}

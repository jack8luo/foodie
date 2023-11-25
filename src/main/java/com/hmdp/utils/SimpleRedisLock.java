package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @auth: luohoa
 * @description: Redis实现分布式锁
 * @create: 2023-11-24-23-03
 */
public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 生成线程唯一标示
    private static final String  prefix_uni = UUID.randomUUID().toString(true)+"-";
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = prefix_uni + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public void delLock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                KEY_PREFIX + Thread.currentThread().getId());
    }
    /*@Override
    public void delLock() {
        // 判断是否是自己的锁
        String threadId = prefix_uni + Thread.currentThread().getId();
        String s = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(s)) {
            //通过del删除锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    //     不是自己的锁不用额外处理
    }*/
}

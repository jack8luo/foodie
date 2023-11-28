package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @auth: luohoa
 * @description: 封装Redis工具类
 * @create: 2023-11-23-19-41
 */
@Component
public class RedisClient {
    @Autowired
    public StringRedisTemplate redisTemplate;
    private static final ExecutorService CACHE_REBULID_EXECUTOR = Executors.newFixedThreadPool(10);

    public void set(String key, Object value, Long time, TimeUnit unit){
        // JSONUtil.toJsonStr(value)将所有的对象都序列化为JSON
        // JSONUtil.toBean(Jsoninform,type)將string:Jsoninform轉爲bean.
        // JSONUtil.toJsonStr("")将对象返回String
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,unit);
    }
    public void setLogicalExpire(String key, Object value, Long time, TimeUnit unit){
    //     设置过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        //     写入redis
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    // 缓存穿透
    public <R,ID> R queryWithPassThrough(
            String keyperfix,ID id,Class<R> type,Function<ID,R> dbFallBack,Long time,TimeUnit unit){
        // 1、根据id查redis
        String key = keyperfix+id;
        String Jsoninform = redisTemplate.opsForValue().get(key);
        // 2、判断是否命中
        if(StrUtil.isNotBlank(Jsoninform)){
            // 3、命中返回商铺信息
            return JSONUtil.toBean(Jsoninform,type);
        }
        // 判断是否是空值
        // tip:StrUtil.isNotBlank(shopform)在shopform真实有值的时候才true
        // null、“”都为false
        // 但是shopform==""为false，因为==是比较两个是否是同一个对象。
        // shopform.equal("")报空指针异常,因为shopform第一次请求为NULL，null不能调用equal方法。
        // 推荐使用"".equals(shopform)
        if("".equals(Jsoninform)){
            return null;
        }
        // 4、未命中查询数据库
        R r = dbFallBack.apply(id);
        // Shop id1 = (Shop) query().eq("id", id).one();
        // 5、不存在返回404
        if (r==null)
        {
            // redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),2L, TimeUnit.MINUTES);
            this.set(key,"",2L,TimeUnit.MINUTES);
            return null;
        }
        // 6、存在写入redis
        // redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),30L, TimeUnit.SECONDS);
        this.set(key,r,time,unit);
        // 7、返回商铺信息
        return r;
    }
    // 通过逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(
            String keyperfix,ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        // 1、根据id查redis
        String key = keyperfix+id;
        String inform = redisTemplate.opsForValue().get(key);
        // 2、判断是否命中
        if(!StrUtil.isNotBlank(inform)){
            // 5.4未命中返回null.
            return null;
        }
        // 3、命中判断是否过期
        // 4 没过期返回商铺信息
        RedisData redisData = JSONUtil.toBean(inform, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        if (expireTime.isAfter(LocalDateTime.now())){
            // 没过期直接返回
            return r;
        }
        // 5过期,重建缓存
        // 5.1获取互斥锁
        String lock = LOCK_SHOP_KEY+id;
        if (BooleanUtil.isTrue(getMutex(lock)))
        {
            // 5.2判断锁获取成功
            // 5.3成功:创建线程进行缓存重建
            CACHE_REBULID_EXECUTOR.submit(() ->{
                try {
                    // 1\查数据库
                    R r1 = dbFallback.apply(id);
                    // 2\存入redis
                    this.setLogicalExpire(keyperfix+id,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    delMutex(lock);
                }
            });
        }
        //     6\获取锁失败,直接返回旧的商铺信息
        return r;
    }
    // 通过互斥锁解决缓存击穿
    public <R,ID> R queryWithMutex(String perfix,ID id,Class<R> type,Function<ID,R> dbFallBack,Long time,TimeUnit unit) {
        // 1、根据id查redis
        String key = perfix+id;
        String inform = redisTemplate.opsForValue().get(key);
        // 2、判断是否命中
        if(StrUtil.isNotBlank(inform)){
            // 3、命中返回商铺信息
            return JSONUtil.toBean(inform,type);
        }
        // 判断是否是空值
        // tip:StrUtil.isNotBlank(shopform)在shopform真实有值的时候才true
        // null、“”都为false
        // 但是shopform==""为false，因为==是比较两个是否是同一个对象。
        // shopform.equal("")报空指针异常,因为shopform第一次请求为NULL，null不能调用equal方法。
        // 推荐使用"".equals(shopform)
        if("".equals(inform)){
            return null;
        }
        // 4、未命中查询数据库
        // 4.1、 获取互斥锁
        String lockKey = "MUTEX_SHOP_KEY" + id;;
        R r = null;
        try { //try final 为了防止因为程序异常中断导致锁最后不释放
            if (!getMutex(lockKey)) {
                // 4.2、 未取到互斥锁等待
                Thread.sleep(50);
                //4.2.1、等待后再重试
                // tip:如果不return，前面找到的shopform将失效
                return queryWithMutex(perfix,id,type,dbFallBack,time,unit);
            }
            // 4.3.0 获得锁成功之后应该做doubleCheck，防止其他线程重复写入缓存
            if (StrUtil.isNotBlank(inform)){
                return JSONUtil.toBean(inform,type);
            }
            // 4.4、模拟远程数据库重建的延时
            // tip: 当数据库堵塞，并发的线程就越多，出现的安全问题来检验锁是否可靠
            Thread.sleep(200);
            // 4.3、查数据库
            r = dbFallBack.apply(id);
            // 5、不存在返回404
            if (r==null)
            {
                // redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),2L, TimeUnit.MINUTES);
                set(key,"",2L,TimeUnit.MINUTES);
                return null;
            }
            // 6、存在写入redis
            // redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),30L, TimeUnit.SECONDS);
            set(key,r,30L,TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6.1、释放互斥锁
            delMutex(lockKey);
        }
        // 7、返回商铺信息
        return r;
    }
    public boolean getMutex(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void delMutex(String key){
        redisTemplate.delete(key);
    }

}

package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisClient;
import com.hmdp.utils.RedisData;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    RedisClient redisClient;
    // 缓存穿透
    @Override
    public Result shopgetById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // Shop shop = redisClient.queryWithPassThrough(
        //         CACHE_SHOP_KEY,id,Shop.class,this::getById,2L,TimeUnit.MINUTES);
        // 缓存击穿
        // Shop shop = queryWithMutex(id);
        Shop shop = redisClient.queryWithMutex(LOCK_SHOP_KEY,id,Shop.class,this::getById,2L,TimeUnit.SECONDS);
        // Shop shop = queryWithLogicalExpire(id);
        // Shop shop = redisClient.queryWithLogicalExpire(
        //         CACHE_SHOP_KEY,id,Shop.class,this::getById,10L,TimeUnit.SECONDS);
        if(shop == null)
        {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    private static final ExecutorService CACHE_REBULID_EXECUTOR = Executors.newFixedThreadPool(10);
    // 逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        // 1、根据id查redis
        String key = ""+id;
        String shopform = stringRedisTemplate.opsForValue().get(key);
        // 2、判断是否命中
        if(!StrUtil.isNotBlank(shopform)){
            // 5.4未命中返回null.
            return null;
        }
        // 3、命中判断是否过期
        // 4 没过期返回商铺信息
        RedisData redisData = JSONUtil.toBean(shopform, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // TODO 不可以吧redisData转成string吗
        //
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        if (expireTime.isAfter(LocalDateTime.now())){
            // 没过期直接返回
            return shop;
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
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    delMutex(lock);
                }
            });
        }
    //     6\获取锁失败,直接返回旧的商铺信息
        return shop;
    }

    // 缓存击穿
    private Shop queryWithMutex(Long id) {
        // 1、根据id查redis
        String key = CACHE_SHOP_KEY+id;
        String shopform = stringRedisTemplate.opsForValue().get(key);
        // 2、判断是否命中
        if(StrUtil.isNotBlank(shopform)){
            // 3、命中返回商铺信息
            return JSONUtil.toBean(shopform,Shop.class);
        }
        // 判断是否是空值
        // tip:StrUtil.isNotBlank(shopform)在shopform真实有值的时候才true
        // null、“”都为false
        // 但是shopform==""为false，因为==是比较两个是否是同一个对象。
        // shopform.equal("")报空指针异常,因为shopform第一次请求为NULL，null不能调用equal方法。
        // 推荐使用"".equals(shopform)
        if("".equals(shopform)){
            return null;
        }
        // 4、未命中查询数据库
        // 4.1、 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try { //try final 为了防止因为程序异常中断导致锁最后不释放
            if (!getMutex(lockKey)) {
                // 4.2、 未取到互斥锁等待
                Thread.sleep(50);
                //4.2.1、等待后再重试
                // tip:如果不return，前面找到的shopform将失效
                return queryWithMutex(id);
            }
            // 4.3.0 获得锁成功之后应该做doubleCheck，防止其他线程重复写入缓存
            if (StrUtil.isNotBlank(shopform)){
                return JSONUtil.toBean(shopform,Shop.class);
            }
            // 4.4、模拟远程数据库重建的延时
            // tip: 当数据库堵塞，并发的线程就越多，出现的安全问题来检验锁是否可靠
            Thread.sleep(200);
            // 4.3、查数据库
            shop = getById(id);
            // 5、不存在返回404
            if (shop==null)
            {
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),2L, TimeUnit.MINUTES);
                return null;
            }
            // 6、存在写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6.1、释放互斥锁
            delMutex(lockKey);
        }
        // 7、返回商铺信息
        return shop;
    }
    // 获取锁和释放锁的方法
    public boolean getMutex(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void delMutex(String key){
        stringRedisTemplate.delete(key);
    }
    // 缓存预热
    // 因为没有向redis直接插入数据的接口，所以用单元测试做缓存预热。
    public void saveShop2Redis(Long id,Long expireSeconds){
        //1、查询店铺信息
        Shop shop = getById(id);
        //2、设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3、写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    // 缓存穿透
    public Shop queryWithPassThrough(Long id){
        // 1、根据id查redis
        String key = CACHE_SHOP_KEY+id;
        String shopform = stringRedisTemplate.opsForValue().get(key);
        // 2、判断是否命中
        if(StrUtil.isNotBlank(shopform)){
            // 3、命中返回商铺信息
            return JSONUtil.toBean(shopform,Shop.class);
        }
        // 判断是否是空值
        // tip:StrUtil.isNotBlank(shopform)在shopform真实有值的时候才true
        // null、“”都为false
        // 但是shopform==""为false，因为==是比较两个是否是同一个对象。
        // shopform.equal("")报空指针异常,因为shopform第一次请求为NULL，null不能调用equal方法。
        // 推荐使用"".equals(shopform)
        if("".equals(shopform)){
            return null;
        }
        // 4、未命中查询数据库
        Shop shop = getById(id);
        // Shop id1 = (Shop) query().eq("id", id).one();
        // 5、不存在返回404
        if (shop==null)
        {
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),2L, TimeUnit.MINUTES);
            return null;
        }
        // 6、存在写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        // 7、返回商铺信息
        return shop;
    }
    // 主动更新：保证数据的一致性
    @Override
    @Transactional
    public Result updateshopinform(Shop shop) {
        // 先操作数据库，再删除缓存
        Long id = shop.getId();
        if(id == null)
        {
            return Result.fail("店铺id关联redis键，不能为空！");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();

    }
}

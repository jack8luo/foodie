package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {


    @Autowired
    ShopServiceImpl shopService;
    @Autowired
    RedisClient redisClient;
    // 缓存预热
    @Test
    void testSaveShop(){
        // shopService.saveShop2Redis(1L,10L);
        Shop byId = shopService.getById(1L);
        redisClient.setLogicalExpire(CACHE_SHOP_KEY+1L,byId,10L, TimeUnit.SECONDS);
    }

    @Autowired
    RedisIdWorker redisIdWorker;
    private static final ExecutorService es = Executors.newFixedThreadPool(500);




    @Test
    void testUniquId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

    }

    /**
     * redis自增从0开始，不用初始化数据
     */
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Test
    void icrTest(){
        long increment = stringRedisTemplate.opsForValue().increment("test");
        System.out.println(increment);
        System.out.println(UUID.randomUUID().toString().replace("-", "").toLowerCase());
        LocalDateTime of = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long epochSecond = of.toEpochSecond(ZoneOffset.UTC);
        System.out.println(of);
    }
}


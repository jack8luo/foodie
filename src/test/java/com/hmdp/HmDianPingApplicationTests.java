package com.hmdp;

import cn.hutool.core.io.FileUtil;
import com.hmdp.controller.UserController;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;

import javax.servlet.http.HttpSession;
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


    //生成1000个token做压力测试
    @Autowired
    UserServiceImpl userService;
    @Autowired
    UserController userController;
    @Test
    void testlogin(){
        for (int i = 8889; i <= 9883; i++) {
            //int转String
            String token = String.valueOf(i);
            token = "1877912" + token;
            System.out.println(RegexUtils.isPhoneInvalid(token));
            //初始化HttpSession,不然会报错
            HttpSession session = new MockHttpSession();
            session.setAttribute("phone",token);
            userController.sendCode(token,session);
            String random = UserHolder.getRandom();
            Result result = userService.login_l(new LoginFormDTO(token, random, ""), session);
            token = (String) result.getData();
            // 将token写入tokens.txt文件中
            FileUtil.appendUtf8String(token+"\n","tokens.txt");
        }
    }


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


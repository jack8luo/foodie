package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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

}

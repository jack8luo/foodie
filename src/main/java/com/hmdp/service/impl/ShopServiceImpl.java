package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
    @Override
    public Result shopgetById(Long id) {
        // 1、根据id查redis
        String key = "KEY:SHOP:ID:"+id;
        String shopform = stringRedisTemplate.opsForValue().get(key);
        // 2、判断是否命中
        if(shopform!=null){
            // 3、命中返回商铺信息
            return Result.ok(JSONUtil.toBean(shopform,Shop.class));
        }
        // 4、未命中查询数据库
        Shop shop = getById(id);
        // Shop id1 = (Shop) query().eq("id", id).one();
        // 5、不存在返回404
        if (shop==null)
        {
            return Result.fail("店铺不存在");
        }
        // 6、存在写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        // 7、返回商铺信息
        return Result.ok(shop);
    }
}

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
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

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
        if(StrUtil.isNotBlank(shopform)){
            // 3、命中返回商铺信息
            return Result.ok(JSONUtil.toBean(shopform,Shop.class));
        }
        // 判断是否是空值
        // tip:StrUtil.isNotBlank(shopform)在shopform真实有值的时候才true
        // null、“”都为false
        // 但是shopform==""为false，因为==是比较两个是否是同一个对象。
        // shopform.equal("")报空指针异常,因为shopform第一次请求为NULL，null不能调用equal方法。
        // 推荐使用"".equals(shopform)
        if("".equals(shopform)){
            return Result.fail("店铺信息不存在");
        }
        // 4、未命中查询数据库
        Shop shop = getById(id);
        // Shop id1 = (Shop) query().eq("id", id).one();
        // 5、不存在返回404
        if (shop==null)
        {
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),2L, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        // 6、存在写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        // 7、返回商铺信息
        return Result.ok(shop);
    }

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
        stringRedisTemplate.delete("CACHE:SHOP:KEY"+id);
        return Result.ok();

    }
}

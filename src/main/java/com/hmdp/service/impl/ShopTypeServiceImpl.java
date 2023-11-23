package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private IShopTypeService typeService;
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    // 1、string类型
    // @Override
    // public Result shoptypelist() {
    //     // 1、查询redis
    //     String key = "KEY:SHOPTYPE:ID:1";
    //     String shoptype = stringRedisTemplate.opsForValue().get(key);
    //     // 2、命中直接返回
    //     if(shoptype!=null){
    //         List<ShopType> shopType = new ArrayList<>();
    //         // tip：JSON.toList将JSON转为List集合对象。
    //         List<ShopType> shopTypeList = JSONUtil.toList(shoptype, ShopType.class);
    //         return Result.ok(shopTypeList);
    //     }
    //     // 3、未命中查数据库
    //     List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
    //     if(typeList == null){
    //         // 4、未命中返回失败
    //         return Result.fail("店铺种类不存在!");
    //     }
    //     // 5、命中存入redis
    //     // tip:JSONUtil.toJsonStr(typeList)将对象转为JSON，与JSON.toList将JSON转为List集合对象-匹配
    //     // StrUtil.toString(typeList)将对象转为String

    //     // String shoptypestr = StrUtil.toString(typeList);
    //     String shoptypestr = JSONUtil.toJsonStr(typeList);
    //     stringRedisTemplate.opsForValue().set(key,shoptypestr);
    //     // 6、返回种类信息
    //     return Result.ok(typeList);
    // }
    // 2、List类型
    @Override
    public Result shoptypelist() {
    String key = "Cache:TypeList:List";
    //@ZYL：先查找缓存
    String shopTypeString = stringRedisTemplate.opsForList().leftPop(key);

    //@ZYL：缓存找到了就直接返回
    if (StrUtil.isNotBlank(shopTypeString)) {
        return Result.ok(JSONUtil.toList(shopTypeString,ShopType.class));
    }

    //@ZYL：缓存找不到(隐含逻辑：if判断失败了，此处相当于else部分 )，再找数据库
    List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForList().leftPush(key, JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }

}

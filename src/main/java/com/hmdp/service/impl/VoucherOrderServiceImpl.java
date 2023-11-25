package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.locks.Lock;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    ISeckillVoucherService iseckillVoucherService;
    @Autowired
    RedisIdWorker redisIdWorker;

    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    RedissonClient redissonClient;
    // 这里只做查询，不用事务。对数据进行操作才加上事务。
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠券
        SeckillVoucher byId1 = iseckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始或者过期
        if (byId1.getBeginTime().isAfter(LocalDateTime.now()) ||byId1.getEndTime().isBefore(LocalDateTime.now())) {
            //3、过期返回
            return Result.fail("活动还未开始");
        }
        //     4、没过期判断库存是否充足
        if( byId1.getStock() <= 0 ){
            //     5、不充足返回
            return Result.fail("库存不足");
        }
        Long user_id = UserHolder.getUser().getId();
        //创建锁对象 这个代码不用了，因为我们现在要使用分布式锁
        // SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + user_id, stringRedisTemplate);
        // boolean b = simpleRedisLock.tryLock(1200);
        Lock lock = redissonClient.getLock("order:" + user_id);
        boolean b = lock.tryLock();
        if (!b)
        {
            return Result.fail("不能重复下单！");
        }
        // intern() 这个方法是从常量池中拿到数据，如果我们直接使用userId.toString() 他拿到的对象实际上是不同的对象，
        // new出来的对象，我们使用锁必须保证锁必须是同一把，所以我们需要使用intern()方法
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // simpleRedisLock.delLock();
            lock.unlock();
        }
        //     ==this.createVoucherOrder(voucherId),这属于事务失效的一种情况
    //     spring AOP机制实现事务，而实现事务需要获取这个类的代理对象，
    //     而this.createVoucherOrder(voucherId)获得的是VoucherOrderServiceImpl这个对象，不是代理对象

    }

    // 在事务中使用锁，这意味着，尽管当前线程的事务尚未完成，其他线程可能已经能够访问被锁保护的资源，这可能导致数据不一致或其他线程看到未提交的数据。
    // 不在函数上加锁，是因为会锁住整个对方法，所有用户共用同一把锁，变成串行执行了，所以要缩小锁的粒度
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //     6、充足：扣减库存
        // 6、1 一人一单
        // 6.1.1 查询数据库
        // tip:和乐观锁解决超卖一样，这样先查再向数据库插入数据是存在并发安全问题的
        // 因为乐观锁是判断数据库里面的数据有没有update，而一人一单是向数据库插入数据，所以乐观锁用不了。这边采用悲观锁。
        // 问？：哪些业务逻辑需要加上悲观锁？答！：当然是影响并发安全的代码全加上悲观锁，
        // 这里查询数据库和插入数据库两个业务导致并发安全问题。所以锁上这两个业务。

        Long user_id = UserHolder.getUser().getId();
        // 6.1.2 有订单返回错误
        int count = query().eq("user_id", user_id).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("已经下过单了！");
        }
        boolean flag = iseckillVoucherService.update().setSql("stock = stock-1")
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if (!flag){
            return Result.fail("库存不足！");
        }
        //     7、创建订单
        //     VoucherOrder在数据库中有：订单id.用户id.代金券id需要赋值
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 订单id
        long l = redisIdWorker.nextId("order");
        voucherOrder.setId(l);
        // 7.2 用户id
        Long id = UserHolder.getUser().getId();
        voucherOrder.setUserId(id);
        // 7.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //     8、返回订单id
        return Result.ok(l);
    }
}

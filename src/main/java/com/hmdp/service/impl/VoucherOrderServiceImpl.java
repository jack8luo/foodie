package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /*秒杀优化：将库存是否足够和一人一单的判断逻辑放入redis中去判断*/
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("SECKILL_SCRIPT.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // seckillVoucher秒杀券购买处理
    //使用消息队列stream
    @Override
    public Result seckillVoucher(Long voucherId) {
        //     0、获取用户id、订单id
        Long userid = UserHolder.getUser().getId();
        //获取订单id
        long orderid = redisIdWorker.nextId("order");
        //     1、执行lua脚本
        Long execute = stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userid.toString(), String.valueOf(orderid)
        );
        //     2、判断结果是否为0
        int r = execute.intValue();
        if (r!=0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //  2.6 获取代理对象
        // 创建代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //     4、返回订单ID
        return Result.ok(orderid);
    }
    /*// seckillVoucher秒杀券购买处理
    @Override
    public Result seckillVoucher(Long voucherId) {
    //     0、获取用户id、订单id
        Long userid = UserHolder.getUser().getId();
        long orderid = redisIdWorker.nextId("order");
        //     1、执行lua脚本
        Long execute = stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userid.toString(), String.valueOf(orderid)
        );
        //     2、判断结果是否为0
        int r = execute.intValue();
        if (r!=0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
    //     3、TODO 保存到阻塞队列
    //     3.1 封装voucherOrder订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userid);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //  2.6 获取代理对象
        // 创建代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //     4、返回订单ID
        return Result.ok(orderid);
    }*/


    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //线程池处理的任务
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XGREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream:orders >
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //2 判断是否获取成功
                    if (read == null || read.isEmpty()) {
                        //2.1 如果失败,说明没有消息,继续下一次循环
                        continue;
                    }
                    //2.1 解析订单信息
                    MapRecord<String, Object, Object> mapRecord = read.get(0);
                    Map<Object, Object> value = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //2.2 如果成功,处理订单
                    handleVoucherOrder(voucherOrder);
                    //3 ACK确认 SACK streams.order g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", mapRecord.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列Pendiglist中的订单信息 XGREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams:order 0
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    //2 判断是否获取成功
                    if (read == null || read.isEmpty()) {
                        //2.1 如果失败,说明没有消息,结束循环
                        break;
                    }
                    //2.1 解析订单信息
                    MapRecord<String, Object, Object> mapRecord = read.get(0);
                    Map<Object, Object> value = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //2.2 如果成功,处理订单
                    handleVoucherOrder(voucherOrder);
                    //3 ACK确认 SACK streams.order g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", mapRecord.getId());
                } catch (Exception e) {
                    log.error("处理pending_list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }
    // 创建Array阻塞队列
    /*private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024 * 1024);
    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/
//  proxy放入类的成员变量,让后续线程使用这个代理对象
    private IVoucherOrderService proxy;
    // 创建订单：使用Redisson分布式可重入锁
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 注意：由于是spring的代理对象（事务）是放在threadLocal中，此时的是子线程，是不能从threadLocal得到东西的

            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return ;
        }

        // 6.扣减库存
        boolean success = iseckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return ;
        }
        save(voucherOrder);
    }



    /*// 这里只做查询，不用事务。对数据进行操作才加上事务。
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

    }*/



    /*// 在事务中使用锁，这意味着，尽管当前线程的事务尚未完成，其他线程可能已经能够访问被锁保护的资源，这可能导致数据不一致或其他线程看到未提交的数据。
    // 不在函数上加锁，是因为会锁住整个对方法，所有用户共用同一把锁，变成串行执行了，所以要缩小锁的粒度
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //  6、充足：扣减库存
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
    }*/
}



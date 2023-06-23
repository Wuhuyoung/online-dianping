package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.ResultMap;
import org.jetbrains.annotations.NotNull;
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
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 基于redis实现优惠券秒杀
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIdWorker.nextId("order");

        //1、执行lua脚本
        Long result = (Long) stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        int r = result.intValue();
        //2、判断结果是否为0
        if(r != 0) {
            //3、不为0，返回错误信息
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //5、返回订单id
        return Result.ok(orderId);
    }

//    /**
//     * 基于redis实现优惠券秒杀
//     * @param voucherId
//     * @return
//     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //用户id
//        Long userId = UserHolder.getUser().getId();
//
//        //1、执行lua脚本
//        Long result = (Long) stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString());
//        int r = result.intValue();
//        //2、判断结果是否为0
//        if(r != 0) {
//            //3、不为0，返回错误信息
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //4、为0，将优惠券id、用户id、订单id保存到阻塞队列中
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //4.1 用户id
//        voucherOrder.setUserId(userId);
//        //4.2 订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //4.3 优惠券id
//        voucherOrder.setVoucherId(voucherId);
//
//        //4.4 保存到阻塞队列中
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        //5、返回订单id
//        return Result.ok(orderId);
//    }

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //类初始化后就要开始执行线程任务
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //消息队列名
    String queueName = "stream.orders";

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息是否获取成功
                    if(list == null || list.isEmpty()) {
                        // 2.1 获取失败，继续下一次循环
                        continue;
                    }
                    // 2.2 获取成功，解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0); //key是消息唯一id，后面ACK确认会使用
                    Map<Object, Object> map = record.getValue(); //键值对
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), false);
                    // 3.扣减库存，创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.发送ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常:", e);
                    handlePendingList();
                }
            }
        }
    }

    /**
     * 处理 pending-list中出现异常的消息
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                // 2.判断消息是否获取成功
                if(list == null || list.isEmpty()) {
                    // 2.1 获取失败，说明pending-list中没有异常消息，结束循环
                    break;
                }
                // 2.2 获取成功，解析消息中的订单信息
                MapRecord<String, Object, Object> record = list.get(0); //key是消息唯一id，后面ACK确认会使用
                Map<Object, Object> map = record.getValue(); //键值对
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), false);
                // 3.扣减库存，创建订单
                handleVoucherOrder(voucherOrder);
                // 4.发送ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pending-list异常:", e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interruptedException) {
                    interruptedException.printStackTrace();
                }
            }
        }
    }

//    // 阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    //线程池处理的任务
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 获取订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 扣减库存，创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("订单处理异常:", e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取代理对象（事务）
        proxy.createVoucherOrder(voucherOrder);

    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1、查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2、判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //秒杀尚未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        //3、判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //秒杀已经结束
//            return Result.fail("秒杀已经结束");
//        }
//        //4、判断库存是否充足
//        if(voucher.getStock() <= 0) {
//            return Result.fail("优惠劵库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        //5、分布式锁解决集群下的互斥锁
//        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        //5.1 尝试获取锁
//        boolean isLock = lock.tryLock(); //默认是不重试，30s自动释放
//
//        if(!isLock) {
//            //5.2 获取锁失败，返回错误信息或重试
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //redis lua保证了一人一单，不用再加锁，不可能有同一个用户的多个线程去操作数据库了
        Long voucherId = voucherOrder.getVoucherId();
        //1、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //set stock = stock - 1
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // where stock > 0
                .update();
        if (!success) {
            log.error("优惠劵库存不足");
        }
        //2、将订单保存
        save(voucherOrder);
    }



    @NotNull
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5、一人一单
        Long userId = UserHolder.getUser().getId();

        //5.1 查询订单
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2 判断是否存在
        if (count > 0) {
            //用户已经购买过优惠券
            return Result.fail("该优惠券只能购买一次");
        }
        //6、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //set stock = stock - 1
                .eq("voucher_id", voucherId)
                //.eq("stock", voucher.getStock()) //where voucher_id = ? and stock = ? 乐观锁会导致同一时刻只有一个线程成功
                .gt("stock", 0) // where stock > 0
                .update();
        if (!success) {
            return Result.fail("优惠劵库存不足");
        }
        //7、新建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1 设置订单id
        long id = redisIdWorker.nextId("order");
        voucherOrder.setId(id);
        //7.2 设置用户id
        userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //7.3 设置优惠券id
        voucherOrder.setVoucherId(voucherId);
        //8、将订单保存
        save(voucherOrder);
        //9、返回订单id
        return Result.ok(id);

    }
}

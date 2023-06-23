package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Slf4j
class TestOrder {
    @Autowired
    private RedisIdWorker idWorker;

    private ExecutorService es = Executors.newFixedThreadPool(300);
    @Resource
    private RedissonClient redissonClient;

    @Test
    void testIdWorker() throws InterruptedException {

        RLock lock = redissonClient.getLock("order");
        boolean isLock = lock.tryLock(1l, TimeUnit.SECONDS);


    }
    @Test
    void setUp() throws InterruptedException {
        RLock lock1 = redissonClient.getLock("lock:order");
        //RLock lock2 = redissonClient2.getLock("lock:order");
        //RLock lock3 = redissonClient3.getLock("lock:order");

        //创建联锁 multiLock
        //RLock lock = redissonClient.getMultiLock(lock1, lock2, lock3);

        //boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        //if(!isLock) {
          //  log.error("获取锁失败");
          //  return;
        //}
    }
}

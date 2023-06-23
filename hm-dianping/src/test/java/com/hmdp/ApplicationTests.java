package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import com.sun.javaws.IconUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
@Slf4j
class ApplicationTests {
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //线程池
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - start));
    }

    private RLock lock;

    @Test
    //测试可重入锁
    void testLock() {
        boolean isLock = lock.tryLock();
        if(!isLock) {
            log.error("获取锁失败...1");
            return;
        }
        try {
            log.info("获取锁成功...1");
            testLock2();
        } finally {
            log.warn("准备释放锁...1");
            lock.unlock();
        }
    }


    void testLock2() {
        boolean isLock = lock.tryLock();
        if(!isLock) {
            log.error("获取锁失败...2");
            return;
        }
        try {
            log.info("获取锁成功...2");
        } finally {
            log.warn("准备释放锁...2");
            lock.unlock();
        }
    }

    /**
     * 测试百万用户的 UV统计
     */
    @Test
    void testHyperLogLog() {
        String[] strings = new String[1000];
        for(int i = 1; i <= 1000000; i++) {
            int j = i % 1000;
            strings[j] = "user_" + i;
            if(j == 0) {
                stringRedisTemplate.opsForHyperLogLog().add("hll1", strings);
            }
        }
        System.out.println("count = " + stringRedisTemplate.opsForHyperLogLog().size("hll1"));
    }
}

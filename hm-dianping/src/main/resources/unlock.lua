-- 这里的 KEYS[1] 就是锁的key，这里的ARGV[1] 就是当前线程标示
-- 获取锁中的线程标识，判断与当前线程是否一致
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致，释放锁
    return redis.call('del', KEYS[1])
end
-- 不一致，直接返回
return 0
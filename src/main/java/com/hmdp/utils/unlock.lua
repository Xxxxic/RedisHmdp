-- 线程标识
-- local threadID = "UUID-31"
-- 锁的key
-- local key = "lock:order:userId"
-- 从Redis中获取锁的当前拥有者
-- local id = redis.call('get', key)

-- 动态传参数
-- KEYS为redis中的lock键值
-- ARGV为线程ID

-- 如果当前线程是锁的拥有者
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 删除锁，释放资源
    return redis.call('del', KEYS[1])
end
-- 如果当前线程不是锁的拥有者，不做任何操作
return 0
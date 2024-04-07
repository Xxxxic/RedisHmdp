-- 完成逻辑：判断一人一单、预减库存
local voucherId = ARGV[1]
local userId = ARGV[2]
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 判下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 1
end

-- 判库存
local stock = tonumber(redis.call('get', stockKey))
if stock == nil then -- 处理stock为nil的情况: 没有优惠卷数据
    return 2
else
    if (stock <= 0) then
        return 2
    end
end

-- 扣减
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0

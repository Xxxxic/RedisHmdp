package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 起始时间 我这里设定的是2022.01.01 00:00:00
    private static final Long BEGIN_TIMESTAMP = 1640995200L;
    // 生成ID长度
    public static final long COUNT_BIT = 32L;

    // 时间戳32位 （根据日期的）自增id 32位
    public long nextId(String keyPrefix) {
        // 1.时间戳
        LocalDateTime now = LocalDateTime.now();
        long currentSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = currentSecond - BEGIN_TIMESTAMP;
        // 2.序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + date);
        // 3.拼接
        return timeStamp << COUNT_BIT | count;
    }

}

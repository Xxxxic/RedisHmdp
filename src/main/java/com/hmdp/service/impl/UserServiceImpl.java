package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public User register(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_" + RandomUtil.randomString(8));
        boolean save = this.save(user);
        if (!save) {
            return null;
        }
        return user;
    }

    @Override
    public Result sign() {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接KEY
        String KeyPrefix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + KeyPrefix;
        // 获取Offset：今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        // 判断是否已经签到
        Boolean isSigned = stringRedisTemplate.opsForValue().getBit(key, dayOfMonth - 1);
        if (BooleanUtil.isTrue(isSigned)) {
            return Result.fail("已经签到过了喔");
        }
        // 写入Redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        log.info("签到成功");
        return Result.ok("签到成功");
    }

    // 统计连续签到天数
    @Override
    public Result signCountContinuous() {
        String Key = USER_SIGN_KEY + UserHolder.getUser().getId().toString()
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int dayOfMonth = LocalDateTime.now().getDayOfMonth();
        // 获取截止今日的签到记录：BITFIELD key GET uDAY 0
        List<Long> res = stringRedisTemplate.opsForValue().
                bitField(Key, BitFieldSubCommands.create().get(
                                BitFieldSubCommands.BitFieldType.unsigned((dayOfMonth)))
                        .valueAt(0));
        if (res == null || res.isEmpty()) {
            return Result.ok(0);
        }
        int count = 0;
        Long num = res.get(0);
        while ((num & 1) != 0) {
            count++;
            num >>= 1;
        }
        return Result.ok(count);
    }

    // 统计当月签到次数
    // bitcount得用execute RedisCallback
    @Override
    public Result signCountMonthTotal() {
        String Key = USER_SIGN_KEY + UserHolder.getUser().getId().toString()
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern(":yyyyMM"));
        Long count = stringRedisTemplate.execute((RedisCallback<Long>) con -> con.bitCount(Key.getBytes()));
        log.info(String.valueOf(count));
        return Result.ok(count);
    }
}

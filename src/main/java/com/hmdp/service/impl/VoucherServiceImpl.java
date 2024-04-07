package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

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
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<String> redisVouchers = stringRedisTemplate.opsForList().range(CACHE_SHOP_VOUCHER_KEY + shopId, 0, -1);
        if (redisVouchers != null && !redisVouchers.isEmpty()) {
            log.info("缓存查到商铺优惠卷");
            List<Voucher> vouchers = redisVouchers.stream()
                    .map(s -> JSONUtil.toBean(s, Voucher.class))
                    .collect(Collectors.toList());
            return Result.ok(vouchers);
        }
        //boolean success = Optional.ofNullable(redisVouchers)
        //        .filter(list -> !list.isEmpty())
        //        .isPresent(list -> {
        //            log.info("缓存查到商铺优惠卷");
        //            List<Voucher> vouchers = list.stream()
        //                    .map(s -> JSONUtil.toBean(s, Voucher.class))
        //                    .collect(Collectors.toList());
        //            return Result.ok(vouchers);
        //        });

        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 保存到Redis中，防止击穿 有没有都存
        redisVouchers = vouchers.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        if(redisVouchers.isEmpty()){
            redisVouchers.add("");
        }
        stringRedisTemplate.opsForList().leftPushAll(CACHE_SHOP_VOUCHER_KEY + shopId, redisVouchers);
        stringRedisTemplate.expire(CACHE_SHOP_VOUCHER_KEY + shopId, CACHE_SHOP_TTL, TimeUnit.SECONDS);

        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // 保存到Redis中
        // TODO: 过期时间
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }
}

package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    /**
     * 秒杀优惠卷
     *
     * @param voucherId 优惠卷ID
     * @return 结果信息
     */
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        // 提交优惠卷id，查询信息
        // 注意：这里voucherId不是主键！
        LambdaQueryWrapper<SeckillVoucher> qw = new LambdaQueryWrapper<>();
        qw.eq(voucherId != null, SeckillVoucher::getVoucherId, voucherId);
        SeckillVoucher seckillVoucher = seckillVoucherService.getOne(qw);

        // 判断起止时间：本地时间判断？
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀还未开始，请耐心等待");
        }
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已经结束！");
        }

        // 判断库存
        if (seckillVoucher.getStock() <= 0) {
            // 无：购买失败
            return Result.fail("优惠券已被抢光了哦，下次记得手速快点");
        }

        // 一人一单逻辑：查存在库存后还得查该用户是否已经抢过优惠卷
        Long userId = UserHolder.getUser().getId();

        // 单机锁实现
        //synchronized (userId.toString().intern()) {
        //    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //    return proxy.CreateVoucherOrder(voucherId, userId);
        //}

        // 分布式锁实现
        RLock lock = redissonClient.getLock("lock:" + userId);
        //boolean success = lock.tryLock(1, 10, TimeUnit.SECONDS);
        boolean success = lock.tryLock();
        if (!success) {
            log.info("用户"+userId+"正在尝试购买多张卷");
            // 说明该用户已经有锁了: 正在尝试购买多张卷
            return Result.fail("不允许抢多张卷");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.CreateVoucherOrder(voucherId, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    // 在锁粒度为用户的情况下（一人一单），进行库存扣减和订单创建
    @Override
    @Transactional
    public Result CreateVoucherOrder(Long voucherId, Long userId) {
        // 判断有没有买过
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("已经抢过优惠券了哦");
        }

        // 库存删一个 - 更新数据库
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //.eq("stock", seckillVoucher.getStock())     // 乐观锁：检查版本号，和进来时是否一样
                .gt("stock", 0)
                .update();
        if (!success)
            return Result.fail("库存不足");

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        long userID = UserHolder.getUser().getId();
        voucherOrder.setUserId(userID);
        save(voucherOrder);

        // 返回订单ID
        return Result.ok("秒杀成功: " + orderId);
    }
}

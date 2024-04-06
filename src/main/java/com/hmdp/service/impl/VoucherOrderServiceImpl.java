package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    /**
     * 秒杀优惠卷
     *
     * @param voucherId 优惠卷ID
     * @return 结果信息
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
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
        // 库存删一个 - 更行数据库
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //.eq("stock", seckillVoucher.getStock())     // 乐观锁：检查版本号，和进来时是否一样
                .gt("stock", 0)         // 悲观锁：Mysql的排他锁
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

        return Result.ok("秒杀成功");
    }
}

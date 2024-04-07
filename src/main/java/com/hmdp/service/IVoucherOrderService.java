package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher_v3(Long voucherId) throws InterruptedException;

    @Transactional
    Result CreateVoucherOrder_v3(VoucherOrder voucherOrder);


    Result seckillVoucher_v2(Long voucherId) throws InterruptedException;

    @Transactional
    Result CreateVoucherOrder_v2(VoucherOrder voucherOrder);


    Result seckillVoucher_v1(Long voucherId) throws InterruptedException;

    @Transactional
    Result CreateVoucherOrder_v1(Long voucherId, Long userId);
}

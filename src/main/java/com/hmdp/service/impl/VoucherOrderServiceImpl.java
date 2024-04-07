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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

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
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    // 导入秒杀Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 单机阻塞队列
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 异步执行下单线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //创建线程任务，秒杀业务需要在类初始化之后，就立即执行，所以这里需要用到@PostConstruct注解
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 从阻塞队列中取出订单创建
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单: 这里不能用下面那个了
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }

    // 这里是不能注入：循环依赖
    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象，作为兜底方案
        RLock redisLock = redissonClient.getLock("order" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 得使用代理对象，因为是另一个线程
            proxy.CreateVoucherOrder_v2(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }

    /**
     * 操作数据库创建订单 v2: 异步阻塞队列存的是订单信息，已经创建好返回给用户了
     * 然后再对数据库操作
     * 如果操作失败就订单作废了 TODO: 订单的状态
     * 返回给用户的订单没有存入数据库，用户拿订单号是查不到的
     *
     * @param voucherOrder 传入已经创建好的订单
     * @return 订单号 || 错误信息
     */
    @Override
    @Transactional
    public Result CreateVoucherOrder_v2(VoucherOrder voucherOrder) {
        // 判断有没有买过
        int count = query().eq("voucher_id", voucherOrder.getVoucherId())
                .eq("user_id", voucherOrder.getUserId()).count();
        if (count > 0) {
            return Result.fail("已经抢过优惠券了哦");
        }

        // 库存删一个 - 更新数据库
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //.eq("stock", seckillVoucher.getStock())     // 乐观锁：检查版本号，和进来时是否一样
                .gt("stock", 0)
                .update();
        if (!success)
            return Result.fail("库存不足");

        // 保存订单
        save(voucherOrder);

        // 返回订单ID
        return Result.ok("秒杀成功: " + voucherOrder.getId());
    }

    /**
     * 秒杀优惠卷 第二版： 缓存优化 + 异步阻塞队列
     *
     * @param voucherId 优惠卷ID
     * @return 结果信息
     */
    @Override
    public Result seckillVoucher_v2(Long voucherId) {
        Long res = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString());
        if (res != null && res.intValue() != 0) {
            return Result.fail(res.intValue() == 1 ? "已经购买过啦" : "库存不足啦");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        long orderId = redisIDWorker.nextId("order");   // 小雪花ID
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 保存到异步阻塞队列
        orderTasks.add(voucherOrder);

        // 主线程获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }


    /**
     * 秒杀优惠卷 第一版：查数据库 + 用户粒度锁
     *
     * @param voucherId 优惠卷ID
     * @return 结果信息
     */
    @Override
    public Result seckillVoucher_v1(Long voucherId) throws InterruptedException {
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

        // 分布式锁实现: Redisson
        RLock lock = redissonClient.getLock("lock:" + userId);
        //boolean success = lock.tryLock(1, 10, TimeUnit.SECONDS);
        boolean success = lock.tryLock();
        if (!success) {
            log.info("用户" + userId + "正在尝试购买多张卷");
            // 说明该用户已经有锁了: 正在尝试购买多张卷
            return Result.fail("不允许抢多张卷");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.CreateVoucherOrder_v1(voucherId, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }


    // 在锁粒度为用户的情况下（一人一单），进行库存扣减和订单创建
    @Override
    @Transactional
    public Result CreateVoucherOrder_v1(Long voucherId, Long userId) {
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

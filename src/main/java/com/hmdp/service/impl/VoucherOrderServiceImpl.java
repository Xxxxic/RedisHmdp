package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT_V2;

    static {
        SECKILL_SCRIPT_V2 = new DefaultRedisScript<>();
        SECKILL_SCRIPT_V2.setLocation(new ClassPathResource("lua/seckill_v2.lua"));
        SECKILL_SCRIPT_V2.setResultType(Long.class);
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT_V3;

    static {
        SECKILL_SCRIPT_V3 = new DefaultRedisScript<>();
        SECKILL_SCRIPT_V3.setLocation(new ClassPathResource("lua/seckill_v3.lua"));
        SECKILL_SCRIPT_V3.setResultType(Long.class);
    }

    // 这里是不能注入：循环依赖
    private IVoucherOrderService proxy;

    // 单机阻塞队列
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 异步执行下单线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //创建线程任务，秒杀业务需要在类初始化之后，就立即执行，所以这里需要用到@PostConstruct注解
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private static final String queueName = "stream.orders";

    private class VoucherOrderHandler implements Runnable {
        // 版本三：从消息队列中拉取消息
        @Override
        public void run() {
            while (true) {
                try {
                    // 感觉不如lua脚本
                    // 获取队列消息
                    // XREADGROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().
                            read(Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                    // ReadOffset.lastConsumed()底层就是 '>'
                                    StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    // 判断有无消息
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    // 获取成功，转成对象
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 执行下单逻辑
                    handleVoucherOrder(voucherOrder);
                    // 手动ACK: SACK stream.order g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                    log.info("消息队列成功处理一条消息");
                } catch (Exception e) {
                    log.error("消息队列：订单处理异常", e);
                    // 没有ACK 放到PENDINGLIST中了
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1. 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    //2. 判断pending-list中是否有未处理消息
                    if (records == null || records.isEmpty()) {
                        //如果没有就说明没有异常消息，直接结束循环
                        break;
                    }
                    //3. 消息获取成功之后，我们需要将其转为对象
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                    handleVoucherOrder(voucherOrder);
                    //5. 手动ACK，SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.info("处理pending-list异常");
                    //如果怕异常多次出现，可以在这里休眠一会儿
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        // 版本二：从阻塞队列取消息
        //@Override
        //public void run() {
        //    while (true) {
        //        try {
        //            // 从阻塞队列中取出订单创建
        //            VoucherOrder voucherOrder = orderTasks.take();
        //            // 创建订单: 这里不能用下面那个第一版
        //            handleVoucherOrder(voucherOrder);
        //        } catch (Exception e) {
        //            log.error("订单处理异常", e);
        //        }
        //    }
        //}
    }

    // 异步线程 - 创建订单
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
     * 优惠卷秒杀 第三版：加消息队列（整合到Lua中）
     */
    @Override
    public Result seckillVoucher_v3(Long voucherId) throws InterruptedException {
        long orderId = redisIDWorker.nextId("order");
        Long res = stringRedisTemplate.execute(SECKILL_SCRIPT_V3,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId));
        if (res.intValue() != 0) {
            return Result.fail(res.intValue() == 1 ? "不能重复下单" : "库存不足");
        }

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    /**
     * 创建订单 第三版
     * <p>
     * 项目启动时，开启一个线程任务，尝试获取stream.orders中的消息，完成下单
     * 从redis的stream消息队列中取出订单信息
     */
    @Override
    public Result CreateVoucherOrder_v3(VoucherOrder voucherOrder) {

        return null;
    }


    /**
     * 秒杀优惠卷 第二版： 缓存优化 + 异步阻塞队列
     *
     * @param voucherId 优惠卷ID
     * @return 结果信息
     */
    @Override
    public Result seckillVoucher_v2(Long voucherId) {
        Long res = stringRedisTemplate.execute(SECKILL_SCRIPT_V2,
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
     * 操作数据库创建订单 v2: 异步阻塞队列存的是订单信息，已经创建好返回给用户了
     * 然后再对数据库操作
     * 如果操作失败就订单作废了
     * TODO: 订单的状态
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

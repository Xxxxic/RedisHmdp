package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private boolean tryLock(String key) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // Boolean取消装箱可能空指针
        return BooleanUtil.isTrue(b);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 用于重构缓存的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据 id 查询商铺
     * <p>
     * 先从Redis中取
     *
     * @param id 商铺ID
     * @return 商铺 不存在返回null
     */
    @Override
    public Shop queryByID(Long id) {
        // 商铺信息以JSON形式存
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 如果不为空（查询到了），则转为Shop类型直接返
        if (StrUtil.isNotBlank(s)) {
            //log.info("从缓存里面查询到商户！");
            return JSONUtil.toBean(s, Shop.class);
        }
        // 查到了空值：防止穿透
        if ("".equals(s)) {
            return null;
        }

        Shop shop;
        try {
            boolean lock = tryLock(LOCK_SHOP_KEY + id);
            if (!lock) {
                Thread.sleep(50);
                return queryByID(id);   // 类递归
            }
            // 查不到去数据库查
            shop = this.getById(id);
            if (shop == null) {
                // 数据不存在写入Redis：存空值 TTL为两分钟
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 查到了先存入Redis再返回
            String shopJSON = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopJSON, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(LOCK_SHOP_KEY + id);
        }

        return shop;
    }

    // 逻辑删除版本
    public Shop queryByID2(Long id) {
        // 商铺信息以JSON形式存
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 空：未命中 或者 空值：防止穿透
        if (StrUtil.isBlank(s) || s.isEmpty()) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(s, RedisData.class);
        // data是以json格式存 得用(JSONObject)转，之后再转成Shop
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)) {
            return shop;    // 未过期
        }
        // 过期 则取锁重建缓存
        boolean lock = tryLock(LOCK_SHOP_KEY + id);
        if (lock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                Shop shopNew;
                try {
                    shopNew = this.saveShop2Redis(id, LOCK_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(LOCK_SHOP_KEY + id);
                }
                return shopNew;    // 重建完毕，返回商铺信息（最新的）
            });
        }
        // 未获取到锁，直接放回商铺信息（过期的）
        return shop;
    }

    // 查数据，不存在存入空值返回false；将数据封装在redisData设置逻辑ttl，然后缓存
    public Shop saveShop2Redis(Long id, Long ttl) {
        Shop shop = this.getById(id);
        if (shop == null) {
            // 数据不存在写入Redis：存空值 TTL为两分钟
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        RedisData redisData = new RedisData();
        redisData.setData(JSONUtil.toJsonStr(shop));
        redisData.setData(LocalDateTime.now().plusSeconds(ttl)); // 逻辑过期时间
        // 没设置过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        return shop;
    }

    /**
     * 实现双写一致
     * 更新时删除缓存：先操作数据库再删除缓存
     *
     * @param shop 更新的商铺信息
     * @return 更新状态
     */
    @Transactional
    @Override
    public boolean updateImpl(Shop shop) {
        if (shop.getId() == null)
            return false;
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return true;
    }

    @Override
    public boolean saveShop2Redis() {
        try {
            List<Shop> shopList = list();
            // 用stream的collections groupingBy 分组
            Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
            for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
                String Key = SHOP_GEO_KEY + entry.getKey();
                List<Shop> shops = entry.getValue();
                //// 按照 typeId - shop写入redis
                //for (Shop shop : shops) {
                //    stringRedisTemplate.opsForGeo().add(Key,
                //            new Point(shop.getX(), shop.getY()),
                //            shop.getId().toString());
                //}

                // 优化：批量写入 - 减少redis连接次数
                List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
                for (Shop shop : shops) {
                    locations.add(new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())));
                }
                stringRedisTemplate.opsForGeo().add(Key, locations);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 分页信息
        int start = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;
        String Key = SHOP_GEO_KEY + typeId;
        // 查询redis、按照距离排序、分页; 结果：shopId、distance
        // 只能查 0 - end 的
        // GEOSEARCH key FROMLONLAT x y BYRADIUS 5000 m WITHDIST
        GeoResults<RedisGeoCommands.GeoLocation<String>> res = stringRedisTemplate.opsForGeo().search(Key,
                GeoReference.fromCoordinate(x, y),
                new Distance(500),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (res == null) {
            return Result.ok(Collections.emptyList());
        }
        // 解析查到到的id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = res.getContent();
        if (list.size() < start) {
            return Result.ok(Collections.emptyList());
        }
        ArrayList<Long> ids = new ArrayList<>(list.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(start).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 用id查询对应商户
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).
                last("ORDER BY FIELD( id," + idsStr + ")").list();
        // 赋值shop的距离属性
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}

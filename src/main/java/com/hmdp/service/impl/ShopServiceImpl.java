package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
        //如果不为空（查询到了），则转为Shop类型直接返
        if (StrUtil.isNotBlank(s)) {
            //log.info("从缓存里面查询到商户！");
            return JSONUtil.toBean(s, Shop.class);
        }

        // 查不到去数据库查
        Shop shop = this.getById(id);
        if (shop == null) {
            return null;
        }
        // 查到了先存入Redis再返回
        String shopJSON = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopJSON, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        //log.info("从数据库里面查询到商户");
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
}

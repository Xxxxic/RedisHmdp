package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 店铺类型信息
     * 存入Redis的List：多个店铺类型信息从JSON和Bean之间相互转换
     * 只是多了一个foreach循环
     *
     * @return List 不存在则为空 null
     */
    @Override
    public List<ShopType> queryList() {
        List<String> shopTypes = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if (shopTypes != null && !shopTypes.isEmpty()) {
            //List<ShopType> res = new ArrayList<>();
            //for (String s : shopTypes) {
            //    res.add(JSONUtil.toBean(s, ShopType.class));
            //}
            return shopTypes.stream().map(s -> JSONUtil.toBean(s, ShopType.class)).collect(Collectors.toList());
        }

        List<ShopType> res = this.query().orderByAsc("sort").list();
        if (res == null) {
            return null;
        }
        // 存入Redis
        //List<String> tmp = new ArrayList<>();
        //for(ShopType shopType : res){
        //    tmp.add(JSONUtil.toJsonStr(shopType));
        //}
        // shopTypes = res.stream().map(shopType -> JSONUtil.toJsonStr(shopType)).collect(Collectors.toList());
        shopTypes = res.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        // TODO: 商铺类型 过期策略？
        stringRedisTemplate.opsForList().leftPushAll(CACHE_SHOP_TYPE_KEY, shopTypes);

        return res;
    }
}

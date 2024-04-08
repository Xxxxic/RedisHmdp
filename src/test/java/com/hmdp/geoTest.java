package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;


@RunWith(SpringRunner.class)
@SpringBootTest
public class geoTest {
    @Resource
    private IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void loadShopData() {
        List<Shop> shopList = shopService.list();
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
    }

}

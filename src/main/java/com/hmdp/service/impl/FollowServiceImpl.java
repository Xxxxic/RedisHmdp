package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    // TODO: 缓存优化 - 直接数据库

    /**
     * 判断当前用户是否关注了该博主
     *
     * @param followUserId 判断被关注的博主
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userid = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userid).
                eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 实现取关/关注
     *
     * @param followUserId 将操作博主的id
     * @param isFollow     当前是否已经关注
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userid = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userid;
        //未关注：关注
        if (!isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userid);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            if (success)
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else { //已关注：取关
            boolean success = remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userid)
                    .eq(Follow::getFollowUserId, followUserId));
            if (success)
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key1 = FOLLOW_KEY + userId;
        String key2 = FOLLOW_KEY + id;

        //对当前用户和博主用户的关注列表取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // redis里面存的是id - 转UserDTO返回
        List<Long> idList = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(idList).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}

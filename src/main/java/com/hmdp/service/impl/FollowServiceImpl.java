package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

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
        //未关注：关注
        if (!isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userid);
            follow.setFollowUserId(followUserId);
            save(follow);
        } else { //已关注：取关
            remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userid)
                    .eq(Follow::getFollowUserId, followUserId));
        }
        return Result.ok();
    }
}

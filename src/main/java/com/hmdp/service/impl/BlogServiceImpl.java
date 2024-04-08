package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result saveBlogWithFeed(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        save(blog);

        // follow表中查粉丝
        List<Follow> followUserId = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送博客
        for (Follow follow : followUserId) {
            Long FanId = follow.getUserId();
            String key = FEED_KEY + FanId;
            stringRedisTemplate.opsForZSet().add(key,
                    blog.getId().toString(),
                    System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * @param current 页数 默认第一页
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        String currentUserId;
        if (UserHolder.getUser() != null)  // 可能出现空指针
            currentUserId = UserHolder.getUser().getId().toString();
        else
            currentUserId = "";
        // 查询用户 & 当前用户是否点赞
        records.forEach(blog -> {
            // 设置博客作者用户
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());

            // 当前用户是否点赞
            Double score = stringRedisTemplate.opsForZSet().
                    score(BLOG_LIKED_KEY + blog.getId(),
                            currentUserId);
            Boolean isLiked = score != null;
            blog.setIsLike(BooleanUtil.isTrue(isLiked));
        });
        return Result.ok(records);
    }


    /**
     * 根据blog id来查询blog
     *
     * @param id 博客id
     */
    @Override
    public Result queryById(Integer id) {
        //Blog blog = blogMapper.selectBlogWithUser(id);
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("该Blog不存在");
        }
        //log.info(blog.toString());
        queryBlogUer(blog);
        return Result.ok(blog);
    }

    /**
     * 修改点赞数: 点过就取消，没点过就点
     * Redis set: key(Bolg_id - userId)
     *
     * @param id 博客id
     */
    @Override
    //public Result likeBlog(Long id) {
    //    // 查看当前用户有没有点赞
    //    Long userId = UserHolder.getUser().getId();
    //    String key = BLOG_LIKED_KEY + id;
    //    Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
    //    if (Boolean.FALSE.equals(isLiked)) {
    //        // 没点过: 修改修改点赞数量
    //        boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
    //        if (success)
    //            stringRedisTemplate.opsForSet().add(key, String.valueOf(userId));
    //    } else {
    //        boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
    //        if (success)
    //            stringRedisTemplate.opsForSet().remove(key, userId.toString());
    //    }
    //    return Result.ok();
    //}
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 没点过: 修改修改点赞数量
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success)
                stringRedisTemplate.opsForZSet().add(key, String.valueOf(userId), System.currentTimeMillis());
        } else {
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success)
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    /**
     * 点赞列表的查询
     * <p>
     * 查询最新点的5个：最新的点的排在前面
     *
     * @param id 博客id
     */
    @Override
    public Result queryBlogLikes(Integer id) {
        // 查出前五个排行的id
        Set<String> top5 = stringRedisTemplate.opsForZSet().
                range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            // 没人点
            return Result.ok(Collections.emptyList());
        }
        // 查出来的结果：没有按照时间排序
        //将ids使用`,`拼接，SQL语句查询出来的结果并不是按照我们期望的方式进行排序
        //所以我们需要用order by field来指定排序方式，期望的排序方式就是按照查询出来的id进行排序
        //select * from tb_user where id in (ids[0], ids[1] ...) order by field(id, ids[0], ids[1] ...)
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idsStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("order by field(id," + idsStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    /**
     * 读Feed推流 关注人的Blog
     *
     * @param max    上次的时间戳
     * @param offset 偏移量
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        // 查询用户收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        // 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 解析数据: blogId、minTime（时间戳）、offset，这里指定创建的list大小，可以略微提高效率，因为我们知道这个list就得是这么大
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1; // 下次偏移量: 几个相同的时间戳
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String id = typedTuple.getValue();  // 博客id
            if (id != null) {
                ids.add(Long.valueOf(id));
            }
            long time = Objects.requireNonNull(typedTuple.getScore()).longValue();  // 时间戳
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        // 根据id查询blog
        String idsStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).
                last("ORDER BY FIELD( id," + idsStr + ")").list();
        for (Blog blog : blogs) {
            queryBlogUer(blog);     // 赋值用户信息
            // 赋值点赞信息
            Double score = stringRedisTemplate.opsForZSet().
                    score(BLOG_LIKED_KEY + blog.getId(),
                            userId);
            Boolean isLiked = score != null;
            blog.setIsLike(BooleanUtil.isTrue(isLiked));
        }

        // 封装结果并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }


    private void queryBlogUer(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }


}

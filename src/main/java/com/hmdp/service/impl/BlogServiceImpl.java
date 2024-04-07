package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
    private StringRedisTemplate stringRedisTemplate;

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
            Boolean isLiked = stringRedisTemplate.opsForSet().
                    isMember(BLOG_LIKED_KEY + blog.getId(),
                            currentUserId);
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
    public Result likeBlog(Long id) {
        // 查看当前用户有没有点赞
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (Boolean.FALSE.equals(isLiked)) {
            // 没点过: 修改修改点赞数量
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success)
                stringRedisTemplate.opsForSet().add(key, String.valueOf(userId));
        } else {
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success)
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    private void queryBlogUer(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }
}

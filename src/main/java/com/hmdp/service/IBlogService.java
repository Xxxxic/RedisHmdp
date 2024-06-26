package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result saveBlogWithFeed(Blog blog);

    Result queryHotBlog(Integer current);

    Result queryById(Integer id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Integer id);

    Result queryBlogOfFollow(Long max, Integer offset);
}

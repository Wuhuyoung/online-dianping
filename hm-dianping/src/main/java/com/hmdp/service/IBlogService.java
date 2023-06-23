package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IBlogService extends IService<Blog> {

    Result queryBlog(Long id);

    Result queryHotBlog(Integer current);

    Result updateBlogLiked(Long id);

    Result queryBlogLikes(Long id);

    List<Blog> listBlogOfUser(Integer currentPage, Long userId);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}

package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// 检测用户登陆状态
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //// 1. 获取session
        //HttpSession session = request.getSession();
        //// 2. 获取用户
        //UserDTO user = (UserDTO) session.getAttribute("user");
        //// 3. 判断用户是否存在
        //if (user == null) {
        //    // 不存在： 拦截
        //    response.setStatus(401);
        //    return false;
        //}
        //// UserHolder中存DTO更加安全
        //UserHolder.saveUser(user);

        // 取出用户
        UserDTO userDTO = UserHolder.getUser();
        // 没登陆就让你走
        if (userDTO == null) {
            response.setStatus(401);
            return false;
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}

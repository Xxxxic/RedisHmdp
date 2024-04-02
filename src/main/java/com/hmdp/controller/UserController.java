package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     * 请求网址: http://localhost:8080/api/user/code?phone=15832165478
     * 请求方法: POST
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // TODO 发送短信验证码并保存验证码
        // 验证手机号/邮箱格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不正确则返回错误信息
            return Result.fail("手机号码格式不正确");
        }
        // 正确则发送验证码
        String code = RandomUtil.randomNumbers(6);
        log.info("code: " + code);
        session.setAttribute(phone, code);

        return Result.ok("发送成功");
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        // TODO 实现登录功能
        log.info(String.valueOf(loginForm));
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        String sessionCode = (String) session.getAttribute(phone);
        // 校验手机格式
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("号码格式不正确");
        }
        // 检验验证码: 空 || 不等
        if (code == null || !code.equals(sessionCode)) {
            return Result.fail("验证码有误");
        }
        // 根据手机号码查询用户
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<>();
        qw.eq(phone != null, User::getPhone, phone);
        User one = userService.getOne(qw);
        if(one == null){
            // 没查到 - 注册
            one = userService.register(phone);
        }
        log.info(String.valueOf(one));
        // 保存UserDTO - 仅存需要信息 保护隐私
        //UserDTO userDTO = new UserDTO();
        //userDTO.setId(one.getId());
        //userDTO.setIcon(one.getIcon());
        //userDTO.setNickName(one.getNickName());
        UserDTO userDTO = BeanUtil.copyProperties(one, UserDTO.class);
        // 查到了 - 写入session
        session.setAttribute("user", userDTO);

        return Result.ok(userDTO);
    }

    /**
     * 登出功能
     *
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout() {
        // TODO 实现登出功能
        UserHolder.removeUser();

        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me() {
        // TODO 获取当前登录的用户并返回
        UserDTO userDTO = UserHolder.getUser();
        return Result.ok(userDTO);
        //return Result.fail("功能未完成");
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
}

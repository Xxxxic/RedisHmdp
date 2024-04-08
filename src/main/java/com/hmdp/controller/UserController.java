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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码并保存验证码
     * 请求网址: /user/code?phone=15832165478
     * 请求方法: POST
     * <p>
     * 原本在Seesion中存放user信息
     * 改到Redis中存：key为前缀+手机号
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 验证手机号/邮箱格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不正确则返回错误信息
            return Result.fail("手机号码格式不正确");
        }
        // 正确则发送验证码
        String code = RandomUtil.randomNumbers(6);
        log.info("code: " + code);

        //session.setAttribute(phone, code);
        log.info(LOGIN_CODE_KEY + phone);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        return Result.ok("发送成功");
    }

    /**
     * 登录功能
     * <p>
     * Redis中存用户信息：用哈希表 列为用户字段
     * key为随机生成token，返回到信息中
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        //log.info(String.valueOf(loginForm));
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        String sessionCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        log.info(sessionCode);
        //String sessionCode = (String) session.getAttribute(phone);
        // 校验手机格式
        if (RegexUtils.isPhoneInvalid(phone)) {
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
        if (one == null) {
            // 没查到 - 注册
            one = userService.register(phone);
        }
        log.info(String.valueOf(one));
        // 保存UserDTO - 仅存需要信息 保护隐私
        UserDTO userDTO = BeanUtil.copyProperties(one, UserDTO.class);
        // 查到了 - 写入session
        //session.setAttribute("user", userDTO);

        // 修改逻辑：保存用户信息到Redis中
        // 随机生成token登陆令牌
        String token = UUID.randomUUID().toString();
        // userDTO转到HashMap存储
        HashMap<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(userDTO.getId()));
        userMap.put("icon", userDTO.getIcon());
        userMap.put("nickname", userDTO.getNickName());
        // 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 设置过期时间，用户设置1小时吧
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 成功删验证码
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

        return Result.ok(token);
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
        return Result.ok("登出成功");
        //return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me() {
        // TODO 获取当前登录的用户并返回
        UserDTO userDTO = UserHolder.getUser();

        return Result.ok(userDTO);
        //return Result.fail("功能未完成");
    }

    // 自己信息Info查询 实际上和下面方法一样
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

    // 查对应用户页 with blog
    @GetMapping("/{id}")
    public Result Userinfo(@PathVariable("id") Long userId) {
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    // 签到功能
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    @GetMapping("/sign/count/continuous")
    public Result signCountContinuous(){
        return userService.signCountContinuous();
    }

    @GetMapping("/sign/count/monthtotal")
    public Result signCountMonthTotal(){
        return userService.signCountMonthTotal();
    }
}

package com.wangguangyang.controller;

import com.wangguangyang.common.Result;
import com.wangguangyang.dto.PhoneLoginDTO;
import com.wangguangyang.dto.RegisterDTO;
import com.wangguangyang.dto.StudentLoginDTO;
import com.wangguangyang.service.UserService;
import com.wangguangyang.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器（登录 / 注册）
 *
 * 是什么：登录和注册相关的接口入口。
 * 干什么：提供「学号密码登录」「手机号验证码登录」「注册」三个接口。
 * 为什么：单独一个 Controller，路径不带 /user 前缀，直接用 /studentlogin、/phonelogin、/register（和拦截器放行路径对应）。
 */
@RestController
@Tag(name = "login", description = "登录相关接口")
public class LoginController {

    @Autowired
    private UserService userService;

    /**
     * 学号密码登录
     * 路径：POST /studentlogin
     */
    @PostMapping("/studentlogin")
    @Operation(summary = "学号密码登录", description = "前端传学号和密码，校验通过返回 token")
    public Result<LoginVO> studentLogin(@RequestBody StudentLoginDTO dto) {
        try {
            return Result.success(userService.studentLogin(dto));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 手机号验证码登录
     * 路径：POST /phonelogin
     */
    @PostMapping("/phonelogin")
    @Operation(summary = "手机号验证码登录", description = "前端传手机号和验证码，校验通过返回 token")
    public Result<LoginVO> phoneLogin(@RequestBody PhoneLoginDTO dto) {
        try {
            return Result.success(userService.phoneLogin(dto));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 注册
     * 路径：POST /register
     */
    @PostMapping("/register")
    @Operation(summary = "注册", description = "前端传姓名、学号、密码、身份证、性别、学院、专业、班级、入学年份")
    public Result<Object> register(@RequestBody RegisterDTO dto) {
        try {
            userService.register(dto);
            return Result.success();
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}

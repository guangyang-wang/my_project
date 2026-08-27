package com.wangguangyang.controller;

import com.wangguangyang.common.Result;
import com.wangguangyang.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("user")
@Tag(name="user",description = "用户相关接口")
public class UserController {

    @Autowired
    private UserService userService;



    @GetMapping("/code")
    @Operation(summary = "获取验证码",description = "用户获取验证码")
    public Result<Object> generateCode(@Parameter(description = "手机号") @RequestParam("phone")String phone ){
        try {
            userService.generateCode(phone);
        }catch (Exception e){
            return Result.error(e.getMessage());
        }
        return Result.success();
    }






}

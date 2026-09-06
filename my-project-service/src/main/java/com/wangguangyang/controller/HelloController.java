package com.wangguangyang.controller;

import com.wangguangyang.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hello")
@Tag(name="hello",description = "测试springboot项目正常启动")
public class HelloController {
    @GetMapping
    @Operation(summary = "测试hello",description = "返回hello，world")
    public Result<String> hello(){
        return Result.success("hello");
    }
}

package com.wangguangyang.controller;

import com.wangguangyang.common.Result;
import com.wangguangyang.service.EnrollService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抢课控制器
 *
 * 是什么：抢课接口的入口。
 * 干什么：提供学生抢课接口，路径统一加 /enroll 前缀。
 * 为什么：抢课是独立的一类资源，单独一个 Controller；学生身份由 JWT 拦截器解析后
 *         放进 UserContext，Service 里直接取，不需要前端传学生 id。
 */
@RestController
@RequestMapping("/enroll")
@Tag(name = "enroll", description = "抢课接口")
public class EnrollController {

    @Autowired
    private EnrollService enrollService;

    /**
     * 抢课
     * 路径：POST /enroll/{courseId}（RESTful，课程 id 放路径变量，学生从登录态取）
     */
    @PostMapping("/{courseId}")
    @Operation(summary = "抢课", description = "学生抢课；校验库存、时间冲突、学分上限后异步落库")
    public Result<Object> enroll(@PathVariable Long courseId) {
        enrollService.enroll(courseId);
        return Result.success();
    }
}

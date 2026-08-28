package com.wangguangyang.controller;

import com.wangguangyang.common.Result;
import com.wangguangyang.dto.CourseAddDTO;
import com.wangguangyang.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程管理控制器
 *
 * 是什么：课程相关接口的入口。
 * 干什么：提供课程的新增（后续还有查询、修改、删除）接口。
 * 为什么：课程是独立的一类资源，单独一个 Controller，路径统一加 /course 前缀。
 */
@RestController
@RequestMapping("/course")
@Tag(name = "course", description = "课程管理接口")
public class CourseController {

    @Autowired
    private CourseService courseService;

    /**
     * 新增课程（排课）
     * 路径：POST /course/add
     */
    @PostMapping("/add")
    @Operation(summary = "新增课程", description = "管理员新增课程，含课程信息、上课时间（时间片列表）、上课教室")
    public Result<Object> add(@RequestBody CourseAddDTO dto) {
        courseService.addCourse(dto);
        return Result.success();
    }
}

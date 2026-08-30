package com.wangguangyang.controller;

import com.wangguangyang.common.Result;
import com.wangguangyang.dto.CourseAddDTO;
import com.wangguangyang.dto.CourseUpdateDTO;
import com.wangguangyang.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /**
     * 删除课程
     * 路径：DELETE /course/{id}（RESTful 风格，课程 id 放路径变量）
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除课程", description = "逻辑删除课程并物理删除其时间片关联；已有学生选课的课程禁止删除")
    public Result<Object> delete(@PathVariable Long id) {
        courseService.deleteCourse(id);
        return Result.success();
    }

    /**
     * 修改课程
     * 路径：PUT /course/update（请求体用 CourseUpdateDTO，前端需传 id）
     */
    @PutMapping("/update")
    @Operation(summary = "修改课程", description = "修改课程基本信息及其排课时间（先删后插时间片关联）")
    public Result<Object> update(@RequestBody CourseUpdateDTO dto) {
        courseService.updateCourse(dto);
        return Result.success();
    }
}

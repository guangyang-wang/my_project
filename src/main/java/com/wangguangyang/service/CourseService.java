package com.wangguangyang.service;

import com.wangguangyang.dto.CourseAddDTO;
import com.wangguangyang.dto.CourseUpdateDTO;

/**
 * 课程业务接口
 *
 * 是什么：课程管理（增删改查）的业务层接口。
 * 干什么：定义课程相关的业务方法，由 CourseServiceImpl 实现。
 * 为什么：和 UserService 一样，接口 + 实现分离，Controller 只依赖接口不依赖实现。
 */
public interface CourseService {

    /**
     * 新增课程（含排课时间、教室）
     *
     * 业务规则：课程基本信息 + 多个时间片 + 教室，一个事务里写完；
     * 保证「同一时间 + 同一教室」只能排一门课（前置查重 + 唯一索引兜底）。
     */
    void addCourse(CourseAddDTO dto);

    /**
     * 删除课程（逻辑删除主表 + 物理删除时间片关联）
     *
     * 业务规则：前端传课程 id；主表 course 逻辑删除（deleted=1），
     * 关联表 course_time 物理删除（一条 SQL 删多行），全程一个事务保证原子性。
     * 删除前校验：课程必须存在，且不能已有学生选课（有选课记录则拒绝删除）。
     */
    void deleteCourse(Long id);

    /**
     * 修改课程（CourseUpdateDTO，含 id）
     *
     * 业务规则：改 course 基本信息 + 「先删后插」course_time 关联，全程事务原子；
     * 时间/教室冲突由唯一索引兜底 + 前置查重（均排除自身）。
     */
    void updateCourse(CourseUpdateDTO dto);
}

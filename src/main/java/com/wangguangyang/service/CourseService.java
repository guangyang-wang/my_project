package com.wangguangyang.service;

import com.wangguangyang.dto.CourseAddDTO;

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
}

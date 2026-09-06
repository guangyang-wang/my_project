package com.wangguangyang.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 新增课程请求参数
 *
 * 是什么：前端（管理员）新增课程时传过来的请求体。
 * 干什么：Controller 用 @RequestBody 接收，Service 拿它插入 course 表 + course_time 表。
 * 为什么：课程基本信息 + 排课信息（上课时间、教室）一起传，Service 里一次事务写完。
 *
 * 字段说明：
 *   - 课程信息字段：和 Course 实体一一对应（除 id、selectedCount、create_time 等自动生成字段）
 *   - timeSlotIds：上课时间，即课程占用的「时间片 id」列表（对应 time_slot 维度表）
 *   - classroom：上课教室，一门课一个教室（所有时间片共用）
 */
@Data
public class CourseAddDTO {

    /** 课程编号（业务唯一，如 CS101） */
    private String courseNo;

    /** 课程名称 */
    private String courseName;

    /** 课程类别：1=必修 2=选修 3=公选 4=实践 */
    private Integer category;

    /** 学分 */
    private BigDecimal credit;

    /** 总学时 */
    private Integer hours;

    /** 任课教师姓名 */
    private String teacherName;

    /** 开课院系/学院 */
    private String college;

    /** 开课专业（可空） */
    private String major;

    /** 校区（可空） */
    private String campus;

    /** 考试形式（可空） */
    private String examType;

    /** 授课语言（可空） */
    private String language;

    /** 开课学期，如 2026-2027-1 */
    private String term;

    /** 选课人数上限（总库存） */
    private Integer capacity;

    /** 选课开始时间（可空） */
    private LocalDateTime selectStartTime;

    /** 选课结束时间（可空） */
    private LocalDateTime selectEndTime;

    /** 限选年级（可空） */
    private String restrictGrade;

    /** 限选专业（可空） */
    private String restrictMajor;

    /** 状态：0=未开放 1=可选 2=已满 3=已结束 4=下架 */
    private Integer status;

    /** 上课时间：课程占用的时间片 id 列表（必填） */
    private List<Long> timeSlotIds;

    /** 上课教室（必填，一门课一个教室） */
    private String classroom;
}

package com.wangguangyang.vo;

import lombok.Data;

/**
 * 排课时间段 VO
 *
 * 是什么：一门课「某一个上课时间 + 教室」的展示对象。
 * 干什么：把 course_time（课程-时间片关联）和 time_slot（时间片维度）两张表的数据拼成一个时间段，给前端展示。
 * 为什么：ES 里只存了课程的精简字段（用于搜索），排课时间在 MySQL 的 course_time + time_slot 两张表里，
 *         搜索返回时需要回查 MySQL 拼出「周几第几节、哪个教室」，所以抽这个 VO 承载。
 */
@Data
public class CourseScheduleVO {

    /** 第几周：1~18 */
    private Integer week;

    /** 星期几：1=周一 … 7=周日 */
    private Integer weekday;

    /** 第几节：1~6 */
    private Integer section;

    /** 上课教室（属于"课程+时间片"组合） */
    private String classroom;
}

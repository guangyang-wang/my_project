package com.wangguangyang.vo;

import com.wangguangyang.entity.CourseDoc;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

/**
 * 课程搜索结果 VO
 *
 * 是什么：一门课程「返回给前端」的搜索结果。
 * 干什么：在 CourseDoc（ES 精简字段）基础上，额外带上这门课的排课时间段列表。
 * 为什么：ES 只存了搜索/列表要用的精简字段，排课时间在 MySQL，查询时回查 MySQL 拼装后一起返回，
 *         让前端一次拿到「课程信息 + 周几第几节 + 教室」。
 *
 * 说明：@EqualsAndHashCode(callSuper = true) / @ToString(callSuper = true) 让 Lombok 生成的方法
 *       把父类 CourseDoc 的字段也算进去，避免编译告警（和 CourseVO extends Course 同理）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class CourseSearchVO extends CourseDoc {

    /** 这门课的所有排课时间段（一周可能多节，一对多） */
    private List<CourseScheduleVO> scheduleList;
}

package com.wangguangyang.vo;

import com.wangguangyang.entity.Course;
import com.wangguangyang.entity.CourseTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

/**
 * 课程查询结果对象（聚合 VO）
 *
 * 是什么：一门课程「返回给前端」的完整信息。
 * 干什么：在 Course 实体的基础上，额外带上这门课的所有时间段列表和实时剩余名额，一起给前端展示。
 * 为什么：course 表和 course_time 表是「一对多」关系，前端详情页/列表页既要看课程基本信息，
 *         也要看它周几第几节上课，所以用「继承实体 + 补充字段」的方式聚合。
 *
 * 说明：
 *   - 继承 Course：直接复用实体的所有字段，不用一个个复制，避免字段重复维护。
 *   - timeList：这门课的所有排课时间段（查 course_time 表按 course_id 得到）。
 *   - remainStock：剩余名额 = capacity - selectedCount（后续抢课上线后改成查 Redis 拿实时值）。
 *   - @EqualsAndHashCode(callSuper = true) / @ToString(callSuper = true)：继承父类时显式声明，
 *     让 Lombok 生成的 equals/hashCode/toString 把父类字段也算进去，避免编译告警。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class CourseVO extends Course {

    /** 这门课的所有排课时间段（一对多） */
    private List<CourseTime> timeList;

    /** 剩余名额（capacity - selectedCount，后续改查 Redis 实时值） */
    private Integer remainStock;
}

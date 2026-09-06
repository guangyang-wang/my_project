package com.wangguangyang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 课程-时间片关联实体类（多对多）
 *
 * 是什么：对应数据库 course_time 表，一条「某门课占用了某个时间片」的关联记录。
 * 干什么：记录课程和它占用哪些时间片之间的对应关系，同时带上教室信息。
 * 为什么：time_slot（时间片）是复用的维度表，不存 course_id；课程「谁在哪个时间片上课」
 *         这个关系必须由这张关联表来承接。
 *
 * 说明：
 *   - classroom 放这里（而非 time_slot）：同一个时间片可能被多门课占用，
 *     教室是「某门课 + 某个时间片」这个组合的属性。
 *   - uk_course_slot(course_id, time_slot_id)：一门课不能重复占用同一个时间片。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("course_time")
public class CourseTime {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 课程ID（关联 course.id） */
    private Long courseId;

    /** 时间片ID（关联 time_slot.id） */
    private Long timeSlotId;

    /** 上课教室（属于"课程+时间片"组合） */
    private String classroom;
}

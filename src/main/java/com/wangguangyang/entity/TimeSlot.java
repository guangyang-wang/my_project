package com.wangguangyang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 时间片实体类（维度表）
 *
 * 是什么：对应数据库 time_slot 表，一条「第几周 + 星期几 + 第几节」的固定时间片。
 * 干什么：把大学里「离散且固定」的排课时间抽象成独立记录，全局复用（18周×7天×6节=756条）。
 * 为什么：排课时间不是连续区间而是离散时间片；拆成维度表后，时间冲突的比对
 *         就从「区间重叠计算」简化成「查是否有相同时间片 id」。
 *
 * 说明：
 *   - 没有 course_id：它是被多门课复用的维度表，不隶属于任何一门课；
 *     课程和它的关系由 course_time（关联表）记录。
 *   - 没有 deleted / 审计字段：它是固定字典数据，启动时初始化一次，之后基本不变。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("time_slot")
public class TimeSlot {

    /** 时间片ID（全局唯一，多门课复用） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 第几周：1~18 */
    private Integer week;

    /** 星期几：1=周一 … 7=周日 */
    private Integer weekday;

    /** 第几节：1~6 */
    private Integer section;
}

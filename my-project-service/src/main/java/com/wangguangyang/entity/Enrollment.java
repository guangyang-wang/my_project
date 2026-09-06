package com.wangguangyang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 选课记录实体类
 *
 * 是什么：对应数据库 enrollment 表，一条「某个学生选了某门课」的记录。
 * 干什么：记录学生选课结果，是「一人一课」「学分上限」「时间冲突」这些判断的数据来源。
 * 为什么：抢课最终要落库成一条选课记录，靠它和唯一索引 uk_student_course(student_id, course_id)
 *         保证同一学生不能重复选同一门课。
 *
 * 说明：
 *   - 冗余字段（student_no / course_no / course_name / credit / term）：选课时把课程信息快照固化下来，
 *     这样即使以后课程改名/改学分，历史选课记录也不受影响，也省去查询时的多表 join。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("enrollment")
public class Enrollment {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生ID（逻辑关联 user.id） */
    private Long studentId;

    /** 学号（冗余，便于查询） */
    private String studentNo;

    /** 课程ID（逻辑关联 course.id） */
    private Long courseId;

    /** 课程编号（冗余） */
    private String courseNo;

    /** 课程名称（快照，选课时固化） */
    private String courseName;

    /** 学分（快照，选课时的学分） */
    private BigDecimal credit;

    /** 学期 */
    private String term;

    /** 状态：0=已选 1=已退课 2=已完成 3=已取消(抢课失败回滚) */
    private Integer status;

    /** 选课时间 */
    private LocalDateTime selectTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0=未删 1=已删（@TableLogic 自动处理） */
    @TableLogic
    private Integer deleted;
}

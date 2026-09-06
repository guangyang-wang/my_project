package com.wangguangyang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 课程实体类
 *
 * 是什么：对应数据库 course 表，是「表的一行 ↔ Java 对象」之间的映射载体。
 * 干什么：MyBatis-Plus 查出的每一行课程数据都会装进这个对象，业务代码拿到的是 Course 对象。
 * 为什么：和 User 一样，MyBatis-Plus 是「实体类 + Mapper(继承 BaseMapper)」两件套。
 *
 * 字段对应规则（和 User 一致）：
 *   - 命名：数据库下划线(course_no) → Java 驼峰(courseNo)，MyBatis-Plus 默认驼峰映射
 *   - 类型：BIGINT→Long、VARCHAR→String、TINYINT→Integer、DECIMAL(3,1)→BigDecimal、DATETIME→LocalDateTime
 *
 * 注解说明：
 *   - @TableName("course")：表名映射。course 不是 MySQL 保留字，不用加反引号（只有 user 表名需要）
 *   - @TableId(type = IdType.AUTO)：主键，数据库自增
 *   - @TableLogic：逻辑删除。deleteById 自动变 UPDATE deleted=1，查询自动带 WHERE deleted=0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("course")
public class Course {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 课程编号（业务唯一，如 CS101） */
    private String courseNo;

    /** 课程名称 */
    private String courseName;

    /** 课程类别：1=必修 2=选修 3=公选 4=实践 */
    private Integer category;

    /** 学分（如 3.0、2.5，支持半学分，用 BigDecimal 避免浮点精度丢失） */
    private BigDecimal credit;

    /** 总学时 */
    private Integer hours;

    /** 任课教师姓名（冗余展示） */
    private String teacherName;

    /** 开课院系/学院 */
    private String college;

    /** 开课专业（null=不限专业） */
    private String major;

    /** 校区 */
    private String campus;

    /** 考试形式：考试/考查/论文/机考 */
    private String examType;

    /** 授课语言：中文/英文/双语 */
    private String language;

    /** 开课学期，如 2026-2027-1 */
    private String term;

    /** 选课人数上限（= 总库存，抢课核心字段） */
    private Integer capacity;

    /** 已选人数（当前库存，异步落库时 +1） */
    private Integer selectedCount;

    /** 选课开始时间（时间窗） */
    private LocalDateTime selectStartTime;

    /** 选课结束时间（时间窗） */
    private LocalDateTime selectEndTime;

    /** 限选年级（逗号分隔，如 2024,2025；null=不限） */
    private String restrictGrade;

    /** 限选专业（逗号分隔；null=不限） */
    private String restrictMajor;

    /** 状态：0=未开放 1=可选 2=已满 3=已结束 4=下架 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0=未删 1=已删（@TableLogic 自动处理；@JsonIgnore 纯内部字段，不返回给前端） */
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}

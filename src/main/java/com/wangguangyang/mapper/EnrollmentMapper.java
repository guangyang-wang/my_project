package com.wangguangyang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangguangyang.entity.Enrollment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 选课记录 Mapper 接口
 *
 * 是什么：对应 enrollment 表的数据访问接口。
 * 干什么：继承 BaseMapper<Enrollment>，负责选课记录的增删查（抢课落库、查已选课程、算学分、判冲突都用它）。
 * 为什么：选课记录是「一人一课」「学分上限」「时间冲突」判断的数据源，标准 CRUD 由 BaseMapper 提供，
 *         需要绕开或跨表 join 的自定义查询（预热 Redis 用）加到这里。
 */
@Mapper
public interface EnrollmentMapper extends BaseMapper<Enrollment> {

    /**
     * 统计学生已选课程的总学分（有效选课，未退课未逻辑删除）
     *
     * 是什么：算某个学生当前已经「有效占用」的学分总数。
     * 干什么：抢课前预热 stu:credit:{studentId} 时，用它回源 MySQL 算出已选学分。
     * 为什么：
     *   - status=0 才算是「已选」（status=1 已退课 / 3 已取消 都不算占用学分）；
     *   - deleted=0 排除逻辑删除的记录（@TableLogic 不会自动加在自定义 SQL 上，要手写）；
     *   - COALESCE(SUM(...), 0)：该学生一条选课记录都没有时返回 0，而不是 NULL，方便调用方直接运算。
     */
    @Select("SELECT COALESCE(SUM(credit), 0) FROM enrollment " +
            "WHERE student_id = #{studentId} AND status = 0 AND deleted = 0")
    BigDecimal sumCreditByStudent(@Param("studentId") Long studentId);

    /**
     * 查询学生已选课程占用的所有时间片 id（预热 stu:times 集合用）
     *
     * 是什么：把学生所有已选课程，join 到 course_time，汇总出他占用的全部时间片 id。
     * 干什么：抢课前预热 stu:times:{studentId} 时回源 MySQL，得到该学生「已经在哪些时间片有课」。
     * 为什么：
     *   - 时间冲突判断要「课程时间片集合 ∩ 学生已占时间片集合」是否有交集，学生已占时间片来自这里；
     *   - 一次 join 查出全部，避免在 Java 里 for 循环逐门课查（N+1 查询）。
     */
    @Select("SELECT ct.time_slot_id FROM course_time ct " +
            "JOIN enrollment e ON ct.course_id = e.course_id " +
            "WHERE e.student_id = #{studentId} AND e.status = 0 AND e.deleted = 0")
    List<Long> listTimeSlotIdsByStudent(@Param("studentId") Long studentId);
}

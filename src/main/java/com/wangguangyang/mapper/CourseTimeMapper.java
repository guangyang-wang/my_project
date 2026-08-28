package com.wangguangyang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangguangyang.entity.CourseTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课程-时间片关联 Mapper 接口
 *
 * 是什么：对应 course_time 表（课程-时间片关联表）的数据访问接口。
 * 干什么：继承 BaseMapper<CourseTime>，负责「课程 ↔ 时间片」关联关系的增删查；
 *         外加一个自定义的批量插入方法 insertBatch。
 * 为什么：一门课占用了哪些时间片（冲突检测）、某个时间片被哪些课占用，都靠这张表查；
 *         排课时要一次性写入多个时间片，批量插入比循环逐条更高效。
 */
@Mapper
public interface CourseTimeMapper extends BaseMapper<CourseTime> {

    /**
     * 批量插入课程-时间片关联（一条 SQL 插多行）
     *
     * 是什么：把一门课的多个时间片，用 <foreach> 拼成一条 INSERT ... VALUES (...),(...),(...)。
     * 干什么：替代 for 循环逐条 insert，减少数据库往返；且一条 INSERT 天然原子（一行撞唯一索引则整条失败）。
     * 为什么：配合事务做到「全部成功或全部失败」；SQL 写在 CourseTimeMapper.xml 里。
     */
    int insertBatch(@Param("list") List<CourseTime> list);
}

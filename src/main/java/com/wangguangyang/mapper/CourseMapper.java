package com.wangguangyang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangguangyang.entity.Course;
import org.apache.ibatis.annotations.Mapper;

/**
 * 课程 Mapper 接口
 *
 * 是什么：MyBatis-Plus 的数据访问接口，对应 course 表的 CRUD 操作。
 * 干什么：继承 BaseMapper<Course> 后，自动获得 insert / deleteById / updateById / selectById / selectList 等
 *         通用方法，不用写任何 SQL。
 * 为什么：和 UserMapper 一样，@Mapper 让 MyBatis 扫描注册这个接口。
 *
 * 说明：标准 CRUD 已由 BaseMapper 提供，先留空；
 *       以后「抢课落库」要用到的条件更新扣库存（incrSelectedCount）等自定义 SQL 再加到这里。
 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {
}

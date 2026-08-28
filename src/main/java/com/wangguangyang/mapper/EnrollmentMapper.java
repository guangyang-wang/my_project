package com.wangguangyang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangguangyang.entity.Enrollment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 选课记录 Mapper 接口
 *
 * 是什么：对应 enrollment 表的数据访问接口。
 * 干什么：继承 BaseMapper<Enrollment>，负责选课记录的增删查（抢课落库、查已选课程、算学分、判冲突都用它）。
 * 为什么：选课记录是「一人一课」「学分上限」「时间冲突」判断的数据源，标准 CRUD 由 BaseMapper 提供。
 */
@Mapper
public interface EnrollmentMapper extends BaseMapper<Enrollment> {
}

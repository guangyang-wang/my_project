package com.wangguangyang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wangguangyang.entity.TimeSlot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 时间片 Mapper 接口
 *
 * 是什么：对应 time_slot 表（时间片维度表）的数据访问接口。
 * 干什么：继承 BaseMapper<TimeSlot>，提供时间片的通用 CRUD
 *         （如初始化灌 756 条数据、按 week/weekday/section 查、时间片 id 反查成「周几第几节」）。
 * 为什么：TimeSlot 是全局复用的维度表，冲突检测时要把时间片 id 反查成具体时间。
 */
@Mapper
public interface TimeSlotMapper extends BaseMapper<TimeSlot> {
}

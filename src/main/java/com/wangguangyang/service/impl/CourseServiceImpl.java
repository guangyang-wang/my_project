package com.wangguangyang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wangguangyang.common.BusinessException;
import com.wangguangyang.dto.CourseAddDTO;
import com.wangguangyang.entity.Course;
import com.wangguangyang.entity.CourseTime;
import com.wangguangyang.mapper.CourseMapper;
import com.wangguangyang.mapper.CourseTimeMapper;
import com.wangguangyang.service.CourseService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 课程业务实现
 *
 * 是什么：CourseService 的实现类，承载课程管理的具体业务逻辑。
 * 干什么：新增课程时，把「课程基本信息 + 排课时间」在一个事务里写进数据库。
 * 为什么：一门课对应多条时间片关联，必须保证「要么全部插入成功，要么全部失败」，
 *         所以用 @Transactional + 唯一索引配合。
 */
@Service
public class CourseServiceImpl implements CourseService {

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private CourseTimeMapper courseTimeMapper;

    /**
     * 新增课程（事务方法）
     *
     * 关键点：@Transactional 让「插入 course + 批量插入 course_time」成为一个原子操作，
     * 任何一个时间片撞唯一索引（教室时间冲突），整个事务回滚，course 记录也不会留下。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addCourse(CourseAddDTO dto) {
        // 1. 校验必填字段
        validate(dto);

        // 2. 课程编号唯一（唯一索引 uk_course_no 兜底，这里前置查一次给友好提示）
        Long count = courseMapper.selectCount(
                new LambdaQueryWrapper<Course>().eq(Course::getCourseNo, dto.getCourseNo())
        );
        if (count != null && count > 0) {
            throw new BusinessException("课程编号已存在");
        }

        // 3. 教室时间冲突（唯一索引 uk_time_classroom 兜底，这里前置查一次给友好提示）
        Long conflict = courseTimeMapper.selectCount(
                new LambdaQueryWrapper<CourseTime>()
                        .in(CourseTime::getTimeSlotId, dto.getTimeSlotIds())
                        .eq(CourseTime::getClassroom, dto.getClassroom())
        );
        if (conflict != null && conflict > 0) {
            throw new BusinessException("该教室在这些时间已被占用");
        }

        // 4. 插入课程（DTO 拷贝到实体，insert 后主键 id 会回填）
        Course course = new Course();
        BeanUtils.copyProperties(dto, course);
        course.setSelectedCount(0);                          // 新课程已选人数 0
        if (course.getStatus() == null) course.setStatus(0); // 默认状态：未开放
        courseMapper.insert(course);

        // 5. 组装时间片关联列表，批量插入（一条 SQL 插多行，替代 for 循环逐条 insert）
        List<CourseTime> timeList = dto.getTimeSlotIds().stream().map(slotId -> {
            CourseTime ct = new CourseTime();
            ct.setCourseId(course.getId());   // 依赖步骤 4 回填的主键
            ct.setTimeSlotId(slotId);
            ct.setClassroom(dto.getClassroom());
            return ct;
        }).collect(Collectors.toList());

        try {
            courseTimeMapper.insertBatch(timeList);
        } catch (DuplicateKeyException e) {
            // 并发兜底：前置查重没拦住，唯一索引拦住了 → 抛业务异常，整个事务回滚
            throw new BusinessException("该教室在这些时间已被占用");
        }
    }

    /**
     * 校验必填字段（course 表里 NOT NULL 且无默认值的字段，以及排课必需字段）
     */
    private void validate(CourseAddDTO dto) {
        if (!StringUtils.hasText(dto.getCourseNo())) throw new BusinessException("课程编号不能为空");
        if (!StringUtils.hasText(dto.getCourseName())) throw new BusinessException("课程名称不能为空");
        if (dto.getCredit() == null) throw new BusinessException("学分不能为空");
        if (dto.getHours() == null) throw new BusinessException("学时不能为空");
        if (!StringUtils.hasText(dto.getTeacherName())) throw new BusinessException("任课教师不能为空");
        if (!StringUtils.hasText(dto.getCollege())) throw new BusinessException("开课学院不能为空");
        if (!StringUtils.hasText(dto.getTerm())) throw new BusinessException("学期不能为空");
        if (dto.getCapacity() == null || dto.getCapacity() <= 0) throw new BusinessException("课程容量必须大于0");
        if (!StringUtils.hasText(dto.getClassroom())) throw new BusinessException("上课教室不能为空");
        if (dto.getTimeSlotIds() == null || dto.getTimeSlotIds().isEmpty()) throw new BusinessException("上课时间不能为空");
    }
}

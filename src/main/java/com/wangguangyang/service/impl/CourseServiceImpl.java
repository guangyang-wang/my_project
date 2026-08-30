package com.wangguangyang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wangguangyang.common.BusinessException;
import com.wangguangyang.dto.CourseAddDTO;
import com.wangguangyang.dto.CourseUpdateDTO;
import com.wangguangyang.entity.Course;
import com.wangguangyang.entity.CourseDoc;
import com.wangguangyang.entity.CourseTime;
import com.wangguangyang.entity.Enrollment;
import com.wangguangyang.mapper.CourseMapper;
import com.wangguangyang.mapper.CourseTimeMapper;
import com.wangguangyang.mapper.EnrollmentMapper;
import com.wangguangyang.repository.CourseDocRepository;
import com.wangguangyang.service.CourseService;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class CourseServiceImpl implements CourseService {

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private CourseTimeMapper courseTimeMapper;

    @Autowired
    private EnrollmentMapper enrollmentMapper;

    @Autowired
    private CourseDocRepository courseDocRepository;

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
        //    先排序再插入：多值 INSERT 是逐行加锁，若不同事务插入顺序不一致，
        //    会形成「A 持有 100 等 101、B 持有 101 等 100」的交叉等待死锁；
        //    排序（锁排序）让所有事务按相同顺序竞争锁，从根上消除死锁风险。
        List<CourseTime> timeList = dto.getTimeSlotIds().stream()
                .sorted()   // 按时间片 id 升序，统一加锁顺序
                .map(slotId -> {
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

        // 6. 同步到 ES（双写）：MySQL 写成功后，把课程写进 ES 供搜索。
        //    ES 是搜索副本，写失败不能拖累主流程，所以同步方法内部 catch 异常只记日志。
        syncToEs(course);
    }

    /**
     * 删除课程（事务方法）
     *
     * 关键点：
     *   - 主表 course 用逻辑删除（deleteById 因 @TableLogic 自动变 UPDATE deleted=1）；
     *   - 关联表 course_time 用物理删除（一条 DELETE ... WHERE course_id=? 删多行）；
     *   - 两步包在 @Transactional 里，任何一步失败整体回滚，不会出现「课程删了、时间片残留」的半删除状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCourse(Long id) {
        // 1. 课程存在性校验（selectById 自动带 WHERE deleted=0，已逻辑删除的课程查不到）
        Course course = courseMapper.selectById(id);
        if (course == null) {
            throw new BusinessException("课程不存在或已被删除");
        }

        // 2. 选课前置校验：已有学生选课则禁止删除，防止留下「指向已删课程」的孤儿选课记录
        Long enrolled = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<Enrollment>().eq(Enrollment::getCourseId, id)
        );
        if (enrolled != null && enrolled > 0) {
            throw new BusinessException("该课程已有 " + enrolled + " 名学生选课，无法删除，请先下架");
        }

        // 3. 逻辑删除主表：@TableLogic 让 deleteById 自动变成 UPDATE course SET deleted=1 WHERE id=?
        courseMapper.deleteById(id);

        // 4. 物理删除关联表：一条 DELETE FROM course_time WHERE course_id=? 删掉该课所有时间片。
        //    为什么不用 for 循环、也不用 <foreach> 拼接？—— 批量 INSERT 要 foreach 是因为要把多行 VALUES 拼进一条 SQL；
        //    而删除是「按条件匹配多行」，一条带 WHERE 的 DELETE 天然就是批量，比 insert 更简单。
        courseTimeMapper.delete(
                new LambdaQueryWrapper<CourseTime>().eq(CourseTime::getCourseId, id)
        );

        // 5. 同步删 ES：课程已逻辑删除，ES 里也要删掉，否则还能被搜到
        deleteFromEs(id);
    }

    /**
     * 修改课程（事务方法）
     *
     * 关键点：
     *   - 复用 CourseUpdateDTO（继承 CourseAddDTO 多加 id），修改时前端必传 id；
     *   - 多对多关联 course_time 采用「先删后插」：删掉旧时间片，再插新时间片，不用 diff 哪些变了；
     *   - 三步（改 course + 删旧关联 + 插新关联）包在 @Transactional 里，任何一步失败整体回滚。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCourse(CourseUpdateDTO dto) {
        // 1. 课程存在性校验（修改必须指定 id）
        if (dto.getId() == null) {
            throw new BusinessException("课程 id 不能为空");
        }
        Course exist = courseMapper.selectById(dto.getId());
        if (exist == null) {
            throw new BusinessException("课程不存在或已被删除");
        }

        // 2. 字段校验（和新增一致；validate 接收 CourseAddDTO，子类 CourseUpdateDTO 可直接传入）
        validate(dto);

        // 3. 课程编号唯一校验，排除自身（.ne 排除当前课程，否则编号没改也会误报「已存在」）
        Long count = courseMapper.selectCount(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getCourseNo, dto.getCourseNo())
                        .ne(Course::getId, dto.getId())
        );
        if (count != null && count > 0) {
            throw new BusinessException("课程编号已存在");
        }

        // 4. 教室时间冲突预校验，排除自身
        //    （删旧关联之前库里还有自己的旧时间片，必须 .ne 排除，否则时间片没改也会误报「已占用」）
        Long conflict = courseTimeMapper.selectCount(
                new LambdaQueryWrapper<CourseTime>()
                        .in(CourseTime::getTimeSlotId, dto.getTimeSlotIds())
                        .eq(CourseTime::getClassroom, dto.getClassroom())
                        .ne(CourseTime::getCourseId, dto.getId())
        );
        if (conflict != null && conflict > 0) {
            throw new BusinessException("该教室在这些时间已被占用");
        }

        // 5. 更新课程基本信息
        //    BeanUtils 拷贝后，selectedCount/updateTime 等 DTO 里没有的字段为 null，
        //    updateById 默认忽略 null 字段，所以已选人数不会被覆盖成 null。
        Course course = new Course();
        BeanUtils.copyProperties(dto, course);
        courseMapper.updateById(course);

        // 6. 先删旧的课程-时间片关联（一条 SQL 删多行）
        courseTimeMapper.delete(
                new LambdaQueryWrapper<CourseTime>().eq(CourseTime::getCourseId, dto.getId())
        );

        // 7. 再插新的关联（批量插入，一条 SQL 插多行）
        //    同样先排序再插入（锁排序），避免不同事务插入顺序交叉导致的死锁。
        List<CourseTime> timeList = dto.getTimeSlotIds().stream()
                .sorted()   // 按时间片 id 升序，统一加锁顺序
                .map(slotId -> {
                    CourseTime ct = new CourseTime();
                    ct.setCourseId(dto.getId());
                    ct.setTimeSlotId(slotId);
                    ct.setClassroom(dto.getClassroom());
                    return ct;
                }).collect(Collectors.toList());

        try {
            courseTimeMapper.insertBatch(timeList);
        } catch (DuplicateKeyException e) {
            // 并发兜底：前置查重没拦住（另一事务刚占了同一时间同一教室），唯一索引拦住。
            // 必须 rethrow 让事务感知并回滚，同时转成业务异常给用户友好提示。
            throw new BusinessException("该教室在这些时间已被占用");
        }

        // 8. 同步到 ES：重新查一次拿最新完整数据（含正确的 selectedCount），
        //    避免用 dto 拷贝出的空字段覆盖 ES 里已有的已选人数。
        Course updated = courseMapper.selectById(dto.getId());
        syncToEs(updated);
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

    /**
     * 同步课程到 ES（双写）
     *
     * 关键点：ES 是「搜索副本」，不是主数据源。同步失败绝不能影响 MySQL 主流程
     * （否则 ES 一挂，课程都加不了了），所以这里 try-catch 吞掉异常只记日志；
     * 数据不一致可以靠后续定时全量重建兜底。
     */
    private void syncToEs(Course course) {
        try {
            CourseDoc doc = new CourseDoc();
            BeanUtils.copyProperties(course, doc);  // 字段名一致，重叠字段自动拷贝
            courseDocRepository.save(doc);           // upsert：id 相同则覆盖
        } catch (Exception e) {
            log.error("课程同步到 ES 失败，courseId={}", course.getId(), e);
        }
    }

    /**
     * 从 ES 删除课程（逻辑删除课程时同步删，避免被搜到）
     */
    private void deleteFromEs(Long id) {
        try {
            courseDocRepository.deleteById(id);
        } catch (Exception e) {
            log.error("课程从 ES 删除失败，courseId={}", id, e);
        }
    }
}

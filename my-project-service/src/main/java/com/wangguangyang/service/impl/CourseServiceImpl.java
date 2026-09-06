package com.wangguangyang.service.impl;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wangguangyang.common.BusinessException;
import com.wangguangyang.common.PageResult;
import com.wangguangyang.dto.CourseAddDTO;
import com.wangguangyang.dto.CourseQueryDTO;
import com.wangguangyang.dto.CourseUpdateDTO;
import com.wangguangyang.entity.Course;
import com.wangguangyang.entity.CourseDoc;
import com.wangguangyang.entity.CourseTime;
import com.wangguangyang.entity.Enrollment;
import com.wangguangyang.entity.TimeSlot;
import com.wangguangyang.mapper.CourseMapper;
import com.wangguangyang.mapper.CourseTimeMapper;
import com.wangguangyang.mapper.EnrollmentMapper;
import com.wangguangyang.mapper.TimeSlotMapper;
import com.wangguangyang.service.CourseService;
import com.wangguangyang.vo.CourseScheduleVO;
import com.wangguangyang.vo.CourseSearchVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private TimeSlotMapper timeSlotMapper;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

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

        // 2. 课程编号唯一校验（唯一索引 uk_course_no 兜底，这里前置查一次给友好提示）
        //    必须绕过逻辑删除过滤：@TableLogic 会让 selectCount 自动加 WHERE deleted=0，
        //    但唯一索引建在物理列上，已逻辑删除的课程依然占着编号，直接 insert 会撞索引，
        //    所以改用自定义 SQL（含已删记录）数物理行，提前抛业务异常。
        Long count = courseMapper.countByCourseNoIgnoreDeleted(dto.getCourseNo(), null);
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
        try {
            courseMapper.insert(course);
        } catch (DuplicateKeyException e) {
            // 并发兜底：前置查重没拦住（另一事务刚插入同一编号），唯一索引拦住 → 转业务异常
            throw new BusinessException("课程编号已存在");
        }

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
        // ES 同步已改为「Canal 订阅 binlog 异步同步」，此处不再手动双写，
        // 事务提交后由 binlog → Canal → RabbitMQ → CourseSyncListener 自动落到 ES。
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
        // 逻辑删除（deleted 0→1）会随 binlog 同步到 ES，由 CourseSyncListener 判断后删 ES 文档，
        // 此处不再手动删 ES。
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

        // 3. 课程编号唯一校验，排除自身（excludeId 排除当前课程，否则编号没改也会误报「已存在」）
        //    同样要绕过逻辑删除过滤，否则把编号改成「已删除课程占用的编号」时 insert 会撞唯一索引。
        Long count = courseMapper.countByCourseNoIgnoreDeleted(dto.getCourseNo(), dto.getId());
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
        // ES 同步交由 Canal 异步完成，不再手动双写。
    }

    /**
     * 搜索课程（查 Elasticsearch）
     *
     * 是什么：把前端传来的查询条件转成 ES 的 BoolQuery，从 course 索引里搜出匹配的课程文档。
     * 干什么：courseNo 用 term（精确匹配 keyword）、courseName 用 match（ik 分词模糊匹配），
     *         两个条件都传是 AND 关系，都不传则查全部；结果分页返回 PageResult。
     * 为什么：
     *   - 查 ES 而不是查 MySQL：课程名/教师/专业要做中文分词搜索，MySQL 的 LIKE 做不到「搜"数学"命中"高等数学"」；
     *   - 用 match 而非 wildcard：match 走 searchAnalyzer(ik_smart) 对查询词分词再和索引词匹配，
     *     这才是 ES 全文检索的正确姿势；wildcard 是通配符匹配，对中文分词没有意义。
     */
    @Override
    public PageResult<CourseSearchVO> searchCourses(CourseQueryDTO dto) {
        // 0. 打印收到的查询条件：排查「编号查询不精确」时，先确认参数有没有真正绑定上
        log.info("搜索课程入参：courseNo={}, courseName={}, pageNum={}, pageSize={}",
                dto.getCourseNo(), dto.getCourseName(), dto.getPageNum(), dto.getPageSize());

        // 1. 组装 BoolQuery：must 之间是 AND 关系，哪个条件有值就加哪个
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        if (StringUtils.hasText(dto.getCourseNo())) {
            // courseNo 是 Keyword 字段，term 精确匹配（不分词）
            boolBuilder.must(q -> q.term(t -> t.field("courseNo").value(dto.getCourseNo())));
        }
        if (StringUtils.hasText(dto.getCourseName())) {
            // courseName 是 Text 字段，match 会用 ik_smart 分词后做全文检索
            boolBuilder.must(q -> q.match(m -> m.field("courseName").query(dto.getCourseName())));
        }

        // 2. 组装分页参数（页码前端从 1 开始，ES 的 PageRequest 从 0 开始，所以要 -1）
        int pageNum = dto.getPageNum() == null || dto.getPageNum() < 1 ? 1 : dto.getPageNum();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() < 1 ? 10 : dto.getPageSize();

        // 3. 用 NativeQuery 把「查询条件 + 分页」打包
        Query query = new Query.Builder().bool(boolBuilder.build()).build();
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(query)
                .withPageable(PageRequest.of(pageNum - 1, pageSize))
                .build();

        // 4. 执行查询，拿 SearchHits（含总条数 + 命中列表）
        SearchHits<CourseDoc> hits = elasticsearchOperations.search(nativeQuery, CourseDoc.class);

        // 5. 从 SearchHit 里取出真正的 CourseDoc
        List<CourseDoc> docs = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        // 6. 回查 MySQL 拼排课时间 + 教室：ES 只存精简字段，排课在 course_time + time_slot 两张表
        Map<Long, List<CourseScheduleVO>> scheduleMap = loadSchedules(docs);

        // 7. 把 CourseDoc 转成 CourseSearchVO，带上 scheduleList 一起返回
        List<CourseSearchVO> records = docs.stream().map(doc -> {
            CourseSearchVO vo = new CourseSearchVO();
            BeanUtils.copyProperties(doc, vo);   // 拷贝父类 CourseDoc 的所有字段
            vo.setScheduleList(scheduleMap.getOrDefault(doc.getId(), Collections.emptyList()));
            return vo;
        }).collect(Collectors.toList());

        PageResult<CourseSearchVO> result = new PageResult<>();
        result.setTotal(hits.getTotalHits());
        result.setRecords(records);
        return result;
    }

    /**
     * 回查 MySQL，把 ES 命中课程的「排课时间 + 教室」拼装成 Map<courseId, 时间段列表>
     *
     * 为什么分两张表查：
     *   - course_time 是「课程 ↔ 时间片」的关联表，存 courseId、timeSlotId、classroom；
     *   - time_slot 是时间片维度表，存 week（第几周）、weekday（周几）、section（第几节）；
     *   - 两张表 join 才能把「课程」映射成「周几第几节在哪个教室」的完整排课信息。
     * 用 in(...) 一次查多条，避免对每门课 for 循环发 SQL（N+1 查询）。
     */
    private Map<Long, List<CourseScheduleVO>> loadSchedules(List<CourseDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. 收集课程 id
        List<Long> courseIds = docs.stream()
                .map(CourseDoc::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (courseIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 2. 查这些课程的所有排课关联（course_time）
        List<CourseTime> courseTimes = courseTimeMapper.selectList(
                new LambdaQueryWrapper<CourseTime>().in(CourseTime::getCourseId, courseIds)
        );
        if (courseTimes.isEmpty()) {
            return Collections.emptyMap();
        }

        // 3. 收集涉及的时间片 id
        List<Long> slotIds = courseTimes.stream()
                .map(CourseTime::getTimeSlotId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // 4. 查时间片维度表，建成 Map<timeSlotId, TimeSlot>，方便下面反查 week/weekday/section
        Map<Long, TimeSlot> slotMap = new HashMap<>();
        if (!slotIds.isEmpty()) {
            List<TimeSlot> slots = timeSlotMapper.selectList(
                    new LambdaQueryWrapper<TimeSlot>().in(TimeSlot::getId, slotIds)
            );
            for (TimeSlot slot : slots) {
                slotMap.put(slot.getId(), slot);
            }
        }

        // 5. 拼装 Map<courseId, List<CourseScheduleVO>>（一条 course_time = 一个时间段）
        Map<Long, List<CourseScheduleVO>> result = new HashMap<>();
        for (CourseTime ct : courseTimes) {
            TimeSlot ts = slotMap.get(ct.getTimeSlotId());
            CourseScheduleVO schedule = new CourseScheduleVO();
            schedule.setClassroom(ct.getClassroom());   // 教室在关联表里
            if (ts != null) {
                schedule.setWeek(ts.getWeek());
                schedule.setWeekday(ts.getWeekday());
                schedule.setSection(ts.getSection());
            }
            result.computeIfAbsent(ct.getCourseId(), k -> new ArrayList<>()).add(schedule);
        }

        // 6. 每门课的排课按「周 → 星期 → 节」升序，方便前端按顺序展示
        for (List<CourseScheduleVO> list : result.values()) {
            list.sort(Comparator
                    .comparing(CourseScheduleVO::getWeek, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(CourseScheduleVO::getWeekday, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(CourseScheduleVO::getSection, Comparator.nullsLast(Integer::compareTo)));
        }
        return result;
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

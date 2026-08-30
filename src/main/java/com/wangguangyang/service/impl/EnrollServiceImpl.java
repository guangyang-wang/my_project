package com.wangguangyang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangguangyang.common.BusinessException;
import com.wangguangyang.common.UserContext;
import com.wangguangyang.config.RabbitConfig;
import com.wangguangyang.dto.EnrollMessage;
import com.wangguangyang.entity.Course;
import com.wangguangyang.entity.CourseTime;
import com.wangguangyang.entity.Enrollment;
import com.wangguangyang.mapper.CourseMapper;
import com.wangguangyang.mapper.CourseTimeMapper;
import com.wangguangyang.mapper.EnrollmentMapper;
import com.wangguangyang.service.EnrollService;
import com.wangguangyang.vo.LoginUser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 抢课业务实现
 *
 * 是什么：抢课功能的核心实现，承载「同步抢课」和「异步落库/回补」两套逻辑。
 * 干什么：
 *   - enroll：懒加载预热 Redis → 执行 Lua 原子判断扣减 → 发 MQ；
 *   - persistEnrollment：消费者调用，事务里扣 MySQL 库存 + 插选课记录；
 *   - compensate：失败时回补 Redis，保证 Redis 和 MySQL 最终一致。
 * 为什么把判断下沉到 Redis：
 *   - 库存、学分、时间冲突是抢课的高频判断条件，直接打 MySQL 会扛不住并发；
 *   - 用 Lua 把「判断 + 扣减」做成一个原子操作，从根上消除「并发都判断通过」导致的超卖。
 */
@Slf4j
@Service
public class EnrollServiceImpl implements EnrollService {

    /** 学分上限 ×10（30 学分 = 300，学分统一乘 10 存整数，避免浮点精度问题） */
    private static final int MAX_CREDIT_TIMES10 = 300;

    /** Redis key 前缀 */
    private static final String STOCK_KEY = "stock:";
    private static final String COURSE_TIMES_KEY = "course:times:";
    private static final String STU_CREDIT_KEY = "stu:credit:";
    private static final String STU_TIMES_KEY = "stu:times:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private CourseTimeMapper courseTimeMapper;

    @Autowired
    private EnrollmentMapper enrollmentMapper;

    /** 抢课 Lua 脚本（从 classpath 加载，返回 Long） */
    private final DefaultRedisScript<Long> enrollScript = new DefaultRedisScript<>();

    @PostConstruct
    public void initScript() {
        enrollScript.setLocation(new ClassPathResource("lua/enroll.lua"));
        enrollScript.setResultType(Long.class);
    }

    @Override
    public void enroll(Long courseId) {
        // 1. 当前登录学生（拦截器解析 JWT 后放 ThreadLocal）
        LoginUser loginUser = UserContext.get();
        if (loginUser == null || loginUser.getId() == null) {
            throw new BusinessException("未登录");
        }
        Long studentId = loginUser.getId();

        // 2. 查课程（主键读一次，拿学分/状态/快照字段；真正的热点「库存」在 Redis，不在这里查）
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException("课程不存在");
        }
        if (course.getStatus() == null || course.getStatus() != 1) {
            throw new BusinessException("课程未开放选课");
        }
        BigDecimal credit = course.getCredit();
        int creditTimes10 = credit.multiply(BigDecimal.TEN).intValue();

        // 3. 懒加载预热：key 不存在才回源 MySQL，之后靠 Lua 实时维护
        ensurePreheated(courseId, studentId, course);

        // 4. 执行 Lua 脚本（原子完成库存/时间/学分判断 + 扣减）
        List<String> keys = Arrays.asList(
                STOCK_KEY + courseId,
                STU_CREDIT_KEY + studentId,
                STU_TIMES_KEY + studentId,
                COURSE_TIMES_KEY + courseId
        );
        Long result = stringRedisTemplate.execute(
                enrollScript,
                keys,
                String.valueOf(creditTimes10),
                String.valueOf(MAX_CREDIT_TIMES10)
        );

        // 5. 按 Lua 返回值处理：非 1 都抛业务异常给前端友好提示
        if (result == null) {
            throw new BusinessException("抢课失败，请重试");
        }
        int code = result.intValue();
        if (code == -1) {
            throw new BusinessException("课程已满");
        } else if (code == -2) {
            throw new BusinessException("与已选课程时间冲突");
        } else if (code == -3) {
            throw new BusinessException("学分已达上限（30 学分）");
        } else if (code != 1) {
            throw new BusinessException("抢课失败，请重试");
        }

        // 6. 组装消息，发 MQ 异步落库（同步返回，不等 MySQL 写完）
        EnrollMessage message = new EnrollMessage();
        message.setStudentId(studentId);
        message.setStudentNo(loginUser.getStudentNo());
        message.setCourseId(courseId);
        message.setCourseNo(course.getCourseNo());
        message.setCourseName(course.getCourseName());
        message.setCredit(credit);
        message.setTerm(course.getTerm());

        try {
            String json = objectMapper.writeValueAsString(message);
            rabbitTemplate.convertAndSend(
                    RabbitConfig.ENROLL_EXCHANGE,
                    RabbitConfig.ENROLL_ROUTING_KEY,
                    json,
                    msg -> {
                        // 消息持久化：RabbitMQ 重启后消息不丢（配合队列 durable=true）
                        msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return msg;
                    },
                    new CorrelationData(studentId + ":" + courseId)
            );
        } catch (Exception e) {
            // 发消息失败 → 回补 Redis，用户下次能重试；否则 Redis 扣了但没落库，白扣一份
            compensate(message);
            log.error("抢课消息发送失败，已回补 Redis。studentId={}, courseId={}", studentId, courseId, e);
            throw new BusinessException("系统繁忙，请稍后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean persistEnrollment(EnrollMessage msg) {
        // 1. 条件更新扣库存（WHERE selected_count < capacity 是数据库层的防超卖兜底）
        int rows = courseMapper.incrSelectedCount(msg.getCourseId());
        if (rows == 0) {
            return false;   // 课程已满，未落库（调用方据此回补 Redis）
        }

        // 2. 插入选课记录（撞 uk_student_course 唯一索引抛 DuplicateKeyException，整个事务回滚）
        Enrollment enrollment = new Enrollment();
        enrollment.setStudentId(msg.getStudentId());
        enrollment.setStudentNo(msg.getStudentNo());
        enrollment.setCourseId(msg.getCourseId());
        enrollment.setCourseNo(msg.getCourseNo());
        enrollment.setCourseName(msg.getCourseName());
        enrollment.setCredit(msg.getCredit());
        enrollment.setTerm(msg.getTerm());
        enrollment.setStatus(0);    // 0=已选
        enrollmentMapper.insert(enrollment);
        return true;
    }

    @Override
    public void compensate(EnrollMessage msg) {
        int creditTimes10 = msg.getCredit().multiply(BigDecimal.TEN).intValue();

        // 1. 库存 +1
        stringRedisTemplate.opsForValue().increment(STOCK_KEY + msg.getCourseId(), 1);
        // 2. 学分减回
        stringRedisTemplate.opsForValue().decrement(STU_CREDIT_KEY + msg.getStudentId(), creditTimes10);
        // 3. 时间片 srem：把这门课占用的时间片从学生已占集合里移除
        String courseTimesKey = COURSE_TIMES_KEY + msg.getCourseId();
        Set<String> courseTimes = stringRedisTemplate.opsForSet().members(courseTimesKey);
        if (courseTimes != null && !courseTimes.isEmpty()) {
            stringRedisTemplate.opsForSet().remove(
                    STU_TIMES_KEY + msg.getStudentId(), courseTimes.toArray(new String[0])
            );
        }
    }

    /**
     * 懒加载预热：key 不存在时回源 MySQL 加载一次，之后靠 Lua 实时维护
     *
     * 为什么用 setIfAbsent / hasKey 判空：
     *   - 并发下多个请求同时发现 key 不存在、都去回源，回源结果一致（幂等），
     *     但若直接 set 会互相覆盖，用 setIfAbsent 保证「只有第一个写进去的生效」；
     *   - hasKey 返回 null（极端情况）也当「不存在」处理，走加载分支。
     */
    private void ensurePreheated(Long courseId, Long studentId, Course course) {
        // 1. 课程库存：capacity - selected_count
        String stockKey = STOCK_KEY + courseId;
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            int stock = course.getCapacity() - (course.getSelectedCount() == null ? 0 : course.getSelectedCount());
            stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(stock));
        }

        // 2. 课程时间片：查 course_time，把 time_slot_id 灌进 Set
        String courseTimesKey = COURSE_TIMES_KEY + courseId;
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(courseTimesKey))) {
            List<CourseTime> courseTimes = courseTimeMapper.selectList(
                    new LambdaQueryWrapper<CourseTime>().eq(CourseTime::getCourseId, courseId)
            );
            List<String> slotIds = courseTimes.stream()
                    .map(CourseTime::getTimeSlotId)
                    .filter(Objects::nonNull)
                    .map(slotId -> String.valueOf(slotId))
                    .collect(Collectors.toList());
            if (!slotIds.isEmpty()) {
                stringRedisTemplate.opsForSet().add(courseTimesKey, slotIds.toArray(new String[0]));
            }
        }

        // 3. 学生已选学分：SUM(credit) ×10，存整数
        String stuCreditKey = STU_CREDIT_KEY + studentId;
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stuCreditKey))) {
            BigDecimal sumCredit = enrollmentMapper.sumCreditByStudent(studentId);
            int creditTimes10 = (sumCredit == null ? BigDecimal.ZERO : sumCredit)
                    .multiply(BigDecimal.TEN).intValue();
            stringRedisTemplate.opsForValue().setIfAbsent(stuCreditKey, String.valueOf(creditTimes10));
        }

        // 4. 学生已占时间片：已选课程 join course_time 的所有 time_slot_id
        String stuTimesKey = STU_TIMES_KEY + studentId;
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stuTimesKey))) {
            List<Long> slotIds = enrollmentMapper.listTimeSlotIdsByStudent(studentId);
            if (slotIds != null && !slotIds.isEmpty()) {
                List<String> ids = slotIds.stream()
                        .filter(Objects::nonNull)
                        .map(slotId -> String.valueOf(slotId))
                        .collect(Collectors.toList());
                stringRedisTemplate.opsForSet().add(stuTimesKey, ids.toArray(new String[0]));
            }
        }
    }
}

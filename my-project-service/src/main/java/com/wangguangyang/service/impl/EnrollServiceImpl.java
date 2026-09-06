package com.wangguangyang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
    private static final String COURSE_INFO_KEY = "course:info:";
    /** 分布式锁 key 前缀（防缓存击穿用，每把锁对应一个缓存 key） */
    private static final String LOCK_KEY = "lock:";

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

    /** Redisson 客户端：提供分布式锁 RLock，用于缓存击穿防护 */
    @Autowired
    private RedissonClient redissonClient;

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

        // 2. 读课程快照（Redis 缓存，key 不存在才回源 MySQL 一次；status/credit 每次都要用）
        Course course = ensureCourseSnapshot(courseId);
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
     * 懒加载课程快照：course:info:{id} 不存在才回源 MySQL 一次，之后都读 Redis
     *
     * 为什么缓存整个 Course 实体：
     *   - status / credit 是每次抢课都要读的（status 门禁 + credit 喂 Lua），
     *     不缓存就得每次查 MySQL，回到 selectById 的老问题；
     *   - 库存、时间片才是竞争热点（Lua 原子维护），这里只缓存「只读配置」。
     * 为什么加分布式锁：缓存 miss 的瞬间（冷启动/失效后第一波），并发请求会同时
     *   selectById 打爆 MySQL，用 Redisson 锁保证只有一个线程回源（缓存击穿防护）。
     */
    private Course ensureCourseSnapshot(Long courseId) {
        String key = COURSE_INFO_KEY + courseId;
        return getOrLoadWithLock(
                LOCK_KEY + key,
                () -> readCourseCache(key),
                () -> loadCourseSnapshot(courseId, key)
        );
    }

    /**
     * 懒加载预热：key 不存在时回源 MySQL 加载一次，之后靠 Lua 实时维护
     *
     * 为什么加分布式锁：库存/时间片/学分这些 key 在 miss 的瞬间，并发请求会同时查 MySQL，
     *   用 Redisson 锁保证「只有一个线程回源」，其余拿锁后双重检查直接读缓存（缓存击穿防护）。
     * 注意：第 1 步「库存」是纯内存计算（capacity - selectedCount），不读 MySQL，所以不加锁。
     */
    private void ensurePreheated(Long courseId, Long studentId, Course course) {
        // 1. 课程库存：capacity - selected_count（无 MySQL 读，不需要锁）
        String stockKey = STOCK_KEY + courseId;
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            int stock = course.getCapacity() - (course.getSelectedCount() == null ? 0 : course.getSelectedCount());
            stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(stock));
        }

        // 2. 课程时间片：查 course_time，把 time_slot_id 灌进 Set（加锁防击穿）
        String courseTimesKey = COURSE_TIMES_KEY + courseId;
        getOrLoadWithLock(
                LOCK_KEY + courseTimesKey,
                () -> readSetOrNull(courseTimesKey),
                () -> {
                    List<CourseTime> courseTimes = courseTimeMapper.selectList(
                            new LambdaQueryWrapper<CourseTime>().eq(CourseTime::getCourseId, courseId)
                    );
                    Set<String> slotIds = courseTimes.stream()
                            .map(CourseTime::getTimeSlotId)
                            .filter(Objects::nonNull)
                            .map(String::valueOf)
                            .collect(Collectors.toSet());
                    if (!slotIds.isEmpty()) {
                        stringRedisTemplate.opsForSet().add(courseTimesKey, slotIds.toArray(new String[0]));
                    }
                    return slotIds;
                }
        );

        // 3. 学生已选学分：SUM(credit) ×10，存整数（加锁防击穿）
        String stuCreditKey = STU_CREDIT_KEY + studentId;
        getOrLoadWithLock(
                LOCK_KEY + stuCreditKey,
                () -> {
                    String v = stringRedisTemplate.opsForValue().get(stuCreditKey);
                    return v == null ? null : Integer.parseInt(v);
                },
                () -> {
                    BigDecimal sumCredit = enrollmentMapper.sumCreditByStudent(studentId);
                    int creditTimes10 = (sumCredit == null ? BigDecimal.ZERO : sumCredit)
                            .multiply(BigDecimal.TEN).intValue();
                    stringRedisTemplate.opsForValue().setIfAbsent(stuCreditKey, String.valueOf(creditTimes10));
                    return creditTimes10;
                }
        );

        // 4. 学生已占时间片：已选课程 join course_time 的所有 time_slot_id（加锁防击穿）
        String stuTimesKey = STU_TIMES_KEY + studentId;
        getOrLoadWithLock(
                LOCK_KEY + stuTimesKey,
                () -> readSetOrNull(stuTimesKey),
                () -> {
                    List<Long> slotIds = enrollmentMapper.listTimeSlotIdsByStudent(studentId);
                    if (slotIds == null || slotIds.isEmpty()) {
                        return Set.of();
                    }
                    Set<String> ids = slotIds.stream()
                            .filter(Objects::nonNull)
                            .map(String::valueOf)
                            .collect(Collectors.toSet());
                    stringRedisTemplate.opsForSet().add(stuTimesKey, ids.toArray(new String[0]));
                    return ids;
                }
        );
    }

    /**
     * 防缓存击穿的模板方法：读缓存 → miss 则加分布式锁 → 双重检查 → 回源 MySQL 写缓存
     *
     * 是什么：把「缓存击穿防护」的标准套路抽成一个通用方法。
     * 干什么：缓存 miss 时用 Redisson 分布式锁保证「只有一个线程回源 MySQL」，
     *         其余线程拿锁后双重检查直接读缓存，避免并发 N 个请求同时打 DB。
     * 为什么需要锁：setIfAbsent 只能保证「写入唯一」，挡不住「N 个线程都去查 MySQL」；
     *   冷启动 / 失效后第一波若不串行化，DB 会被瞬间打爆（缓存击穿）。
     *
     * @param lockKey      分布式锁 key（每个缓存 key 对应一把锁）
     * @param readCache    读缓存，返回 null 表示 miss
     * @param loadAndWrite 回源 MySQL 并写回缓存，返回结果
     */
    private <T> T getOrLoadWithLock(String lockKey, Supplier<T> readCache, Supplier<T> loadAndWrite) {
        // 1. 先读缓存：命中直接返回，不碰锁（快路径）
        T value = readCache.get();
        if (value != null) {
            return value;
        }

        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            // tryLock(等待时间, 持有时间, 单位)：
            //   - 最多等 1s，拿不到就走降级分支，避免请求一直阻塞；
            //   - 持有 3s 后自动释放（回源查库是毫秒级，3s 足够；传 -1 则改用看门狗自动续期）。
            locked = lock.tryLock(1, 3, TimeUnit.SECONDS);
            if (locked) {
                try {
                    // 2. 双重检查：抢到锁后别的线程可能已重建缓存
                    value = readCache.get();
                    if (value != null) {
                        return value;
                    }
                    // 3. 回源 + 写缓存
                    return loadAndWrite.get();
                } finally {
                    // 只释放自己持有的锁，防止误删别人刚拿到的锁
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // 4. 没抢到锁：睡 50ms 再读缓存，仍 miss 才降级直接回源（不无限等）
                Thread.sleep(50);
                value = readCache.get();
                return value != null ? value : loadAndWrite.get();
            }
        } catch (InterruptedException e) {
            // 线程被中断：恢复中断标记，直接回源兜底，不阻塞业务
            Thread.currentThread().interrupt();
            return loadAndWrite.get();
        }
    }

    /** 读课程快照缓存：反序列化 JSON；key 不存在或 JSON 损坏返回 null（当 miss 处理） */
    private Course readCourseCache(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Course.class);
        } catch (JsonProcessingException e) {
            log.error("课程快照反序列化失败，按 miss 处理。key={}", key, e);
            return null;
        }
    }

    /** 回源 MySQL 查课程并写回缓存 */
    private Course loadCourseSnapshot(Long courseId, String key) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            return null;
        }
        try {
            stringRedisTemplate.opsForValue().setIfAbsent(key, objectMapper.writeValueAsString(course));
        } catch (JsonProcessingException e) {
            // 序列化失败（几乎不可能）：缓存写不进也不影响，直接返回查到的对象
            log.error("课程快照序列化失败。courseId={}", courseId, e);
        }
        return course;
    }

    /** 读 Set 缓存：key 存在返回元素集合，不存在返回 null（区分「空」和「未加载」） */
    private Set<String> readSetOrNull(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))
                ? stringRedisTemplate.opsForSet().members(key)
                : null;
    }
}

package com.wangguangyang.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangguangyang.config.RabbitConfig;
import com.wangguangyang.dto.EnrollMessage;
import com.wangguangyang.entity.Enrollment;
import com.wangguangyang.mapper.EnrollmentMapper;
import com.wangguangyang.service.EnrollService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 抢课异步落库消费者（RabbitMQ → MySQL）
 *
 * 是什么：监听抢课队列，把「Redis 预扣成功」的抢课请求真正落进 MySQL。
 * 干什么：收到一条抢课消息（学生 + 课程快照），扣课程库存 + 插入选课记录；
 *         失败时回补 Redis，保证 Redis 和 MySQL 最终一致。
 * 为什么：
 *   - 抢课接口用 Redis 预扣后立即同步返回，MySQL 的写由这里异步完成，削峰；
 *   - 落库的 persistEnrollment 是 @Transactional 方法，通过注入的 EnrollService 代理调用，事务才生效。
 *
 * 幂等与失败处理：
 *   - 已存在选课记录 → 直接跳过（重复消费幂等）；
 *   - 课程已满（条件更新 0 行）→ 回补 Redis + ack 丢弃（不重试，避免死循环）；
 *   - 撞唯一索引（极端并发重复）→ 回补 Redis + ack 丢弃；
 *   - 其它系统异常（DB 挂等）→ 回补 Redis + 抛异常让 MQ 重试。
 */
@Slf4j
@Component
public class EnrollConsumer {

    @Autowired
    private EnrollmentMapper enrollmentMapper;

    @Autowired
    private EnrollService enrollService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = RabbitConfig.ENROLL_QUEUE)
    public void onEnrollMessage(String json) {
        // 1. 解析消息（失败则丢弃，避免坏消息反复 requeue）
        EnrollMessage msg;
        try {
            msg = objectMapper.readValue(json, EnrollMessage.class);
        } catch (Exception e) {
            log.error("解析抢课消息失败，丢弃。原始消息: {}", json, e);
            return;
        }

        // 2. 幂等：该学生已选过这门课则直接跳过
        Long exists = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<Enrollment>()
                        .eq(Enrollment::getStudentId, msg.getStudentId())
                        .eq(Enrollment::getCourseId, msg.getCourseId())
        );
        if (exists != null && exists > 0) {
            log.info("选课记录已存在，幂等跳过。studentId={}, courseId={}", msg.getStudentId(), msg.getCourseId());
            return;
        }

        // 3. 事务落库（扣库存 + 插记录）
        try {
            boolean ok = enrollService.persistEnrollment(msg);
            if (!ok) {
                // 课程已满：回补 Redis + ack 丢弃
                enrollService.compensate(msg);
                log.warn("课程已满（条件更新 0 行），回补 Redis。studentId={}, courseId={}",
                        msg.getStudentId(), msg.getCourseId());
            }
        } catch (DuplicateKeyException e) {
            // 撞唯一索引 = 重复选课，回补 + ack 丢弃
            enrollService.compensate(msg);
            log.warn("重复选课（唯一索引拦截），回补 Redis。studentId={}, courseId={}",
                    msg.getStudentId(), msg.getCourseId());
        } catch (Exception e) {
            // 其它异常（DB 挂等）：回补 + 抛异常交给 MQ 重试
            enrollService.compensate(msg);
            log.error("抢课落库异常，已回补并交给 MQ 重试。studentId={}, courseId={}",
                    msg.getStudentId(), msg.getCourseId(), e);
            throw e;
        }
    }
}

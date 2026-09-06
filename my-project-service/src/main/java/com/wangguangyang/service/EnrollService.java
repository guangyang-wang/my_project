package com.wangguangyang.service;

import com.wangguangyang.dto.EnrollMessage;

/**
 * 抢课业务接口
 *
 * 是什么：抢课功能的业务层接口。
 * 干什么：定义抢课（同步）和抢课落库/回补（异步消费用）三个方法，由 EnrollServiceImpl 实现。
 * 为什么：
 *   - 和 CourseService、UserService 一样，接口 + 实现分离，Controller 只依赖接口不依赖实现；
 *   - persistEnrollment / compensate 是 @Transactional 方法，必须通过接口代理调用才能让事务生效，
 *     所以消费者（EnrollConsumer）也注入本接口来调用，而不是直接依赖实现类。
 */
public interface EnrollService {

    /**
     * 抢课（同步）
     *
     * 业务规则：一个学生抢一门课，必须同时满足
     *   1. 课程还有剩余名额（库存）；
     *   2. 与学生已选课程时间不冲突（同一时间片只能上一门课）；
     *   3. 学生已选总学分不超过 30。
     * 三个条件全部在 Redis 里用 Lua 原子判断 + 扣减，通过后发 MQ 异步落库。
     *
     * @param courseId 要抢的课程 id（学生身份从登录上下文 UserContext 取）
     */
    void enroll(Long courseId);

    /**
     * 异步落库（@Transactional）：扣 MySQL 库存 + 插入选课记录
     *
     * 干什么：消费者收到抢课消息后调用，把「Redis 预扣的结果」真正落进 MySQL。
     * 为什么条件更新 + 事务：
     *   - incrSelectedCount 带 WHERE selected_count < capacity，是数据库层的防超卖兜底；
     *   - 扣库存和插记录在同一个事务里，任一步失败整体回滚，不会出现「库存扣了但没选课记录」。
     *
     * @param msg 抢课消息（含学生/课程快照字段）
     * @return true=落库成功；false=课程已满（条件更新 0 行，未落库）
     */
    boolean persistEnrollment(EnrollMessage msg);

    /**
     * 回补 Redis：把 Lua 已经扣减的东西加回去
     *
     * 干什么：抢课发 MQ 失败、或落库失败（超卖/重复/异常）时调用，恢复 Redis 里的
     *         库存、学分、时间片，让用户下次还能重试抢。
     * 为什么：Lua 是「先扣后落库」，落库失败若不回补，Redis 库存就白白少了一份，
     *         和 MySQL 对不上。
     *
     * @param msg 抢课消息（含 courseId、studentId、credit，据此定位要回补哪些 key）
     */
    void compensate(EnrollMessage msg);
}

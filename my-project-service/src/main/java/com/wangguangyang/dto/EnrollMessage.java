package com.wangguangyang.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 抢课 MQ 消息体
 *
 * 是什么：抢课接口在 Redis 预扣成功后，发到 RabbitMQ 抢课队列的消息内容。
 * 干什么：携带「谁抢了哪门课 + 落库需要的快照字段」，让消费者异步写 enrollment + 扣库存。
 * 为什么：
 *   - 冗余字段（courseNo / courseName / credit / term）在抢课时从课程表读一次并快照固化，
 *     消费者直接拿来写 enrollment，不用再回查课程表，也和课程后续变更解耦；
 *   - studentNo 从当前登录用户（JWT）拿，一并塞进消息，消费者不再依赖登录上下文。
 */
@Data
public class EnrollMessage {

    /** 学生ID（= user.id） */
    private Long studentId;

    /** 学号（冗余快照） */
    private String studentNo;

    /** 课程ID */
    private Long courseId;

    /** 课程编号（冗余快照） */
    private String courseNo;

    /** 课程名称（快照，选课时固化） */
    private String courseName;

    /** 学分（快照，原始值如 3.0、2.5） */
    private BigDecimal credit;

    /** 学期（快照） */
    private String term;
}

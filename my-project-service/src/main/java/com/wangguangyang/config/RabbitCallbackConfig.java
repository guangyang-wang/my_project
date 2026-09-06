package com.wangguangyang.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * RabbitTemplate 回调配置类（发送者可靠性）
 *
 * 是什么：定义「消息发送之后」的回调，让发送者能感知消息是否成功到达交换机/队列。
 * 干什么：声明 ConfirmCallback 和 ReturnsCallback 两个 Bean，Spring Boot 自动配置会自动把它们
 *         set 到 RabbitTemplate 上（不用自己 new RabbitTemplate，避免覆盖自动配置）。
 * 为什么：默认发消息是"发出去就不管"（fire-and-forget），交换机写错、路由失败都会静默丢消息，
 *         配了回调才能知道发送结果，这是"发送者可靠性"的关键。
 */
@Configuration
public class RabbitCallbackConfig {

    /**
     * 发布确认回调（ConfirmCallback）—— 全局一个
     *
     * 是什么：消息「到达交换机」后，RabbitMQ 回 ack/nack 时触发。
     * 干什么：ack=true 表示消息到了交换机；ack=false 表示没到（比如交换机名写错）。
     *
     * 为什么是全局（只配这一个）：回调参数只有 CorrelationData(编号) 和 ack 状态，没有消息内容，
     *         所以不需要按消息区分，全局一个回调处理所有消息的确认即可。
     *         "区分是哪条消息"靠的是发消息时传的 CorrelationData.getId()。
     */
    @Bean
    public RabbitTemplate.ConfirmCallback confirmCallback() {
        return (correlationData, ack, cause) -> {
            String id = correlationData == null ? "null" : correlationData.getId();
            if (ack) {
                System.out.println("【消息确认成功】ID = " + id + "，已到达交换机");
            } else {
                System.out.println("【消息确认失败】ID = " + id + "，原因：" + cause);
            }
        };
    }

    /**
     * 发布退回回调（ReturnsCallback）—— 全局一个
     *
     * 是什么：消息到达交换机，但路由不到任何队列（routing key 不匹配）时触发。
     * 干什么：把被退回的消息打印出来，方便排查"为什么没进队列"。
     *
     * 为什么是全局（只配这一个）：回调参数 ReturnedMessage 里【直接带着被退回的消息本体】，
     *         天然能定位是哪条，所以全局一个回调即可，不需要像 confirm 那样每次发消息传标识。
     */
    @Bean
    public RabbitTemplate.ReturnsCallback returnsCallback() {
        return returned -> {
            System.out.println("【消息被退回】交换机=" + returned.getExchange()
                    + "，路由键=" + returned.getRoutingKey()
                    + "，原因=" + returned.getReplyText()
                    + "，消息=" + new String(returned.getMessage().getBody(), StandardCharsets.UTF_8));
        };
    }
}

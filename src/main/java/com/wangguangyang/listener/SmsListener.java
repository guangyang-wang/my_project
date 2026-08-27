package com.wangguangyang.listener;

import com.wangguangyang.config.RabbitConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 短信监听器（RabbitMQ 消费者）
 *
 * 是什么：消息的接收方，负责消费「短信验证码」消息。
 * 干什么：监听 code 队列，队列里一有消息就自动调用 handleSms 方法，模拟"发短信"。
 * 为什么：发短信是慢操作，接口只负责把消息丢进队列就返回，真正"发短信"由这里异步完成，
 *         实现了「发送短信」和「接口响应」的解耦。
 */
@Component
public class SmsListener {

    /**
     * 监听 code 队列，收到消息就执行
     *
     * @RabbitListener(queues = ...)：声明式监听，Spring 会自动为这个队列建立监听容器，
     *         持续监听，有消息就反射调用本方法。
     *
     * 方法参数 String message：默认的 SimpleMessageConverter 会把消息体反序列化成字符串，
     *         所以这里直接声明 String 就能对上。
     *
     * 默认自动 ack：方法执行完，消息就从队列删除。这里只打印不会抛异常，自动 ack 足够，
     *         暂时不用管手动 ack。
     */
    @RabbitListener(queues = RabbitConfig.SMS_QUEUE)
    public void handleSms(String message) {
        // message 形如 "13812345678:123456"，用冒号拆出手机号和验证码
        String[] parts = message.split(":");
        String phone = parts[0];
        String code = parts[1];
        System.out.println("【模拟发短信】给 " + phone + " 发送验证码：" + code);
        // 将来这里换成真的调用短信服务商接口
    }
}

package com.wangguangyang.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 *
 * 是什么：声明 RabbitMQ 里要用到的队列(Queue)、交换机(Exchange)、绑定(Binding)等基础设施。
 * 干什么：把这些基础设施声明成 Spring Bean，项目启动时 Spring 会自动在 RabbitMQ 服务端创建它们。
 * 为什么：队列如果不提前声明，消费者监听一个不存在的队列会直接报错；生产者发消息也会失败。
 *
 * 当前只声明了一个「短信验证码队列」，将来需要交换机做路由/广播时，再往这里加 Exchange 和 Binding 的 Bean。
 */
@Configuration
public class RabbitConfig {

    /*sms是短消息的意思*/
    /**
     * 队列名常量
     *
     * 为什么抽成常量：生产者和消费者都要用同一个队列名，抽成常量能避免两边手写字符串
     * 不小心写错（比如一边写 sms.queue、一边写 sms_queue），导致"发了消息却没人收"的诡异问题。
     */
    public static final String SMS_QUEUE = "code";

    /**
     * 交换机名常量
     * 为什么抽成常量：和队列名一样，生产者在 convertAndSend 时要指定交换机名，抽成常量避免写错。
     */
    public static final String SMS_EXCHANGE = "code.exchange";

    /**
     * 路由键(routing key)常量
     * 为什么抽成常量：Direct 交换机靠 routing key 精确匹配，发送方和绑定方都要用它，抽成常量保证一致。
     */
    public static final String SMS_ROUTING_KEY = "code";

    /**
     * 声明一个短信验证码队列
     *
     * new Queue(队列名, durable)：
     *   - durable = true：队列持久化到磁盘，RabbitMQ 重启后队列本身还在
     *     （注意：队列持久化 ≠ 消息持久化，消息是否持久化要在发消息时单独指定 deliveryMode）。
     *   - exclusive = false（默认）：非排他，多个连接可以共享这个队列。
     *   - autoDelete = false（默认）：即使没有消费者，也不会自动删除队列。
     */
    @Bean
    public Queue smsQueue() {
        return new Queue(SMS_QUEUE, true);
    }

    /**
     * 声明一个 Direct 交换机
     *
     * 是什么：DirectExchange 是「直连」类型交换机，路由规则是 routing key 完全相等才投递。
     * 干什么：作为消息的中转站，接收生产者发来的消息，按 routing key 精确路由到绑定的队列。
     * 为什么：默认交换机只能做到「routing key = 队列名」这一种点对点，显式声明后可以自由定义
     *         交换机名和 routing key，让生产者和队列彻底解耦。
     *
     * new DirectExchange(交换机名, durable, autoDelete)：
     *   - durable = true：交换机持久化，RabbitMQ 重启后交换机还在。
     *   - autoDelete = false：即使没有队列绑定，也不自动删除交换机。
     */
    @Bean
    public DirectExchange smsExchange() {
        return new DirectExchange(SMS_EXCHANGE, true, false);
    }

    /**
     * 把队列绑定到交换机上（用 routing key 连接起来）
     *
     * 是什么：Binding 是「交换机 ↔ 队列」之间的连线规则，核心是绑定时指定的 routing key。
     * 干什么：声明后，凡是发到 sms.exchange 且 routing key = "sms" 的消息，都会被路由进 code 队列。
     * 为什么：交换机本身不存消息，必须靠 Binding 告诉它「符合什么条件就投给哪个队列」，
     *         否则交换机收了消息也不知道该发给谁。
     *
     * BindingBuilder.bind(队列).to(交换机).with(routing key)：链式写法，语义一目了然。
     */
    @Bean
    public Binding smsBinding(Queue smsQueue, DirectExchange smsExchange) {
        return BindingBuilder.bind(smsQueue).to(smsExchange).with(SMS_ROUTING_KEY);
    }

    /* ==================== Canal 同步 ES 相关（双写一致性） ==================== */

    /**
     * Canal 队列名常量
     *
     * 来源：D:\canal\canal.deployer-1.1.7\conf\canal.properties 里的 rabbitmq.queue = canal.queue。
     * 为什么抽成常量：Canal 那边已经把队列声明好了，Spring Boot 这边监听同一个队列，
     *         抽成常量避免两处（Canal 配置 vs 代码）手写字符串不一致。
     */
    public static final String CANAL_QUEUE = "canal.queue";

    /**
     * Canal 交换机名常量
     *
     * 来源：canal.properties 里的 rabbitmq.exchange = canal.exchange。
     */
    public static final String CANAL_EXCHANGE = "canal.exchange";

    /**
     * Canal 路由键常量
     *
     * 来源：conf\example\instance.properties 里的 canal.mq.topic = canal
     *         （Canal 发送时用 instance 的 topic 作为 routing key）。
     */
    public static final String CANAL_ROUTING_KEY = "canal";

    /**
     * 声明 Canal 消息队列
     *
     * 是什么：Canal 订阅 binlog 后，把变更消息发到这个队列，Spring Boot 消费端监听它。
     * 干什么：声明成 Bean，Spring Boot 启动时在 RabbitMQ 服务端创建（若已存在则幂等跳过）。
     * 为什么：
     *   - 队列如果不提前声明，@RabbitListener 监听一个不存在的队列会启动报错；
     *   - durable=true：队列持久化，RabbitMQ 重启后队列还在，不丢消息。
     */
    @Bean
    public Queue canalQueue() {
        return new Queue(CANAL_QUEUE, true);
    }

    /**
     * 声明 Canal 交换机（direct 类型）
     *
     * 是什么：Canal 发消息的中转站，类型必须和 Canal 配置的 rabbitmq.exchange.type = direct 一致。
     * 干什么：声明成 Bean 保证交换机存在，Canal 发来的消息能正常路由。
     * 为什么：Spring Boot 独立启动时也能建好交换机，不依赖 Canal 先启动；
     *         durable=true 持久化，autoDelete=false 不自动删除。
     */
    @Bean
    public DirectExchange canalExchange() {
        return new DirectExchange(CANAL_EXCHANGE, true, false);
    }

    /**
     * 把 Canal 队列绑定到 Canal 交换机（routing key = canal）
     *
     * 干什么：让 Canal 发到 canal.exchange、routing key = canal 的消息，路由进 canal.queue，
     *         被我们的 @RabbitListener 消费。
     * 为什么：绑定规则必须和 Canal 端的声明一致（exchange + routing key + 队列三者对齐），
     *         否则消息会「发出来了但没人收」或「路由不到队列被丢弃」。
     */
    @Bean
    public Binding canalBinding(Queue canalQueue, DirectExchange canalExchange) {
        return BindingBuilder.bind(canalQueue).to(canalExchange).with(CANAL_ROUTING_KEY);
    }

    /* ==================== 抢课异步落库相关 ==================== */

    /**
     * 抢课队列名常量
     *
     * 干什么：抢课接口在 Redis 预扣成功后，发一条消息到这个队列，由 EnrollConsumer 异步落库。
     * 为什么抽成常量：生产者和消费者都要用同一个队列名，抽成常量避免两边手写字符串写错。
     */
    public static final String ENROLL_QUEUE = "enroll.queue";

    /**
     * 抢课交换机名常量
     */
    public static final String ENROLL_EXCHANGE = "enroll.exchange";

    /**
     * 抢课路由键常量
     */
    public static final String ENROLL_ROUTING_KEY = "enroll";

    /**
     * 声明抢课队列（durable=true 持久化，RabbitMQ 重启后队列还在）
     */
    @Bean
    public Queue enrollQueue() {
        return new Queue(ENROLL_QUEUE, true);
    }

    /**
     * 声明抢课交换机（direct 类型，按 routing key 精确路由）
     */
    @Bean
    public DirectExchange enrollExchange() {
        return new DirectExchange(ENROLL_EXCHANGE, true, false);
    }

    /**
     * 把抢课队列绑定到抢课交换机（routing key = enroll）
     */
    @Bean
    public Binding enrollBinding(Queue enrollQueue, DirectExchange enrollExchange) {
        return BindingBuilder.bind(enrollQueue).to(enrollExchange).with(ENROLL_ROUTING_KEY);
    }
}

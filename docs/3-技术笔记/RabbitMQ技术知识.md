# RabbitMQ 技术知识

> 本文档整理 RabbitMQ 在登录功能（发短信验证码）中的用法，涵盖依赖引入、核心概念（队列/交换机/绑定/vhost）、使用方法、消息属性、以及消息可靠性三个层面。

## 一、RabbitMQ 是什么

RabbitMQ 是一个**消息中间件**，核心思想是「**异步解耦**」：生产者把消息丢给中间件就完事，消费者慢慢处理，两边互不阻塞。

在登录场景里，它的典型作用是「**发短信验证码**」：

```
用户点"获取验证码"
   ↓
接口生成验证码 → 存 Redis（快，同步）
   ↓ 把「手机号:验证码」丢进队列（秒回）
接口立刻返回"成功"（用户不用等短信）
   ↓（另一条线程）
消费者收到消息 → 调用短信服务商（慢，异步）
```

> **为什么要用它**：发短信是「慢操作」（要调第三方接口，几百毫秒甚至更久）。如果同步做，接口要等短信真正发完才返回。用队列解耦后，接口只负责丢消息，用户体验更好。

---

## 二、需要引入的依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

**干什么**：Spring 官方的 RabbitMQ 启动器（AMQP 是 RabbitMQ 用的消息协议），引入后能拿到两个核心工具：

| 工具 | 类型 | 用途 |
|------|------|------|
| `RabbitTemplate` | 发消息 | 把消息发到交换机/队列（类比 `RedisTemplate`） |
| `@RabbitListener` | 收消息 | 监听队列，收到消息就执行方法 |

**内部做了什么**：
- 传递引入 `spring-amqp` 和 `amqp-client`（RabbitMQ 官方底层客户端），不用管底层
- 自动读取 `application.yml` 里的 `spring.rabbitmq.*` 配置，自动建好连接工厂（`CachingConnectionFactory`）
- `RabbitTemplate` 默认自动配好，直接 `@Autowired` 就能用

---

## 三、准备工作（环境 + 配置）

### 1. 服务端与端口

| 端口 | 用途 |
|------|------|
| `5672` | **AMQP 协议端口**，程序（Spring Boot）连的是它 |
| `15672` | **HTTP 管理界面**（浏览器访问 `http://localhost:15672`） |

> 注意：`starter-amqp` 只是「客户端」，还得有一个「服务端」在跑。程序里的 `RabbitTemplate` 本质是往 `5672` 发 TCP 连接投递消息。

### 2. vhost（虚拟主机）

- **是什么**：vhost 是 RabbitMQ 里的「逻辑隔离单元」，可以理解成大 RabbitMQ 里隔出的「独立小房间」，各自有独立的队列、交换机、绑定关系。
- **为什么**：默认只有一个 `/` vhost，多个系统都往里塞队列容易命名冲突、权限混乱。规范做法是给每个项目单独建一个 vhost。
- **关键规则**：vhost 名字**必须以 `/` 开头**，如 `/my_project`。

```bash
# 命令行创建
rabbitmqctl add_vhost /my_project
# 管理界面：Admin → Virtual Hosts → Add a new virtual host
```

### 3. 用户与授权（最容易踩的坑）

- **为什么**：默认账号 `guest/guest` **只能从 localhost 连接，且只能访问 `/` vhost**。一旦用了新 vhost，`guest` 操作不了，必须新建专用用户。

一个用户要授三种权限：

| 权限 | 含义 |
|------|------|
| `configure` | 创建/删除交换机、队列 |
| `write` | 往交换机发消息 |
| `read` | 从队列读消息 |

```bash
rabbitmqctl add_user myuser 123456
rabbitmqctl set_permissions -p /my_project myuser ".*" ".*" ".*"
# 管理界面：Admin → Users → Add a user，再点用户名 → Set permission 选 vhost
```

### 4. 配置文件里加连接信息

```yaml
spring:
  rabbitmq:
    host: 192.168.100.128     # 服务端地址
    port: 5672                # AMQP 端口（默认 5672，可不写）
    username: myuser          # 第 3 步建的用户
    password: "123456"        # 密码（0 开头必须加引号，见第八章）
    virtual-host: /my_project # 连到第 2 步建的 vhost
```

> **关键**：`virtual-host` 不配默认连 `/`，程序会去 `/` 里找队列，消息和监听器就「对不上号」。

---

## 四、核心概念详解

### 1. 完整消息流转模型

```
生产者 ──发消息──▶ 交换机(Exchange) ──按 routing key 路由──▶ 队列(Queue) ──▶ 消费者
                        │
                        └── 靠「绑定(Binding)」决定：哪个 routing key → 哪个队列
```

三个核心概念必须一起记：

| 概念 | 作用 | 类比（快递） |
|------|------|--------------|
| **Exchange** 交换机 | 收消息 + 路由 | 分拣中心 |
| **Queue** 队列 | 存消息 + 投递 | 收件人的信箱 |
| **Binding** 绑定 | 交换机↔队列的连线，带 routing key | 地址/邮编 |

> **关键点**：交换机**不存消息**，消息存在队列里。交换机只是「看一眼 routing key，然后转发」。

### 2. 交换机四种类型

| 类型 | 路由规则 | 场景 |
|------|----------|------|
| **Direct**（直连） | routing key **完全相等**才投递 | 点对点，精确匹配 |
| **Fanout**（广播） | **忽略** routing key，广播给所有绑定队列 | 一条消息所有订阅者都收到 |
| **Topic**（主题） | routing key 用 `*`、`#` **通配符**匹配 | 灵活路由，最常用 |
| **Headers** | 按消息头属性匹配（很少用） | 特殊场景 |

### 3. 默认交换机（Default Exchange）

- **是什么**：AMQP 规定每个 vhost 都有一个内置交换机，名字是**空字符串 `""`**。
- **特殊规则**：每个队列自动绑定到默认交换机，且**绑定用的 routing key = 队列名**。
- **所以**：`convertAndSend("code", msg)` 等于「发给默认交换机，routing key 是 `code`」，消息精准落进 `code` 队列。

> 换句话说：**你一直在用交换机，只是用了那个隐藏的默认交换机**，所以不用显式声明。

---

## 五、如何使用（三个角色）

用 RabbitMQ 本质就是三个角色各干一件事：

| 角色 | 用什么 | 干什么 |
|------|--------|--------|
| 声明基础设施 | `Queue`/`Exchange`/`Binding` 的 `@Bean` | 告诉 RabbitMQ 有哪些队列/交换机 |
| 生产者（发） | `RabbitTemplate.convertAndSend()` | 把消息丢进队列 |
| 消费者（收） | `@RabbitListener` 注解 | 监听队列，收到就执行方法 |

### 1. 声明基础设施（配置类）

```java
@Configuration
public class RabbitConfig {

    public static final String SMS_QUEUE = "code";            // 队列名
    public static final String SMS_EXCHANGE = "sms.exchange"; // 交换机名
    public static final String SMS_ROUTING_KEY = "sms";       // 路由键

    // 队列：durable=true 队列持久化到磁盘
    @Bean
    public Queue smsQueue() {
        return new Queue(SMS_QUEUE, true);
    }

    // Direct 交换机：durable=true 持久化，autoDelete=false 不自动删除
    @Bean
    public DirectExchange smsExchange() {
        return new DirectExchange(SMS_EXCHANGE, true, false);
    }

    // 绑定：把队列绑到交换机，routing key = "sms"
    @Bean
    public Binding smsBinding(Queue smsQueue, DirectExchange smsExchange) {
        return BindingBuilder.bind(smsQueue).to(smsExchange).with(SMS_ROUTING_KEY);
    }
}
```

### 2. 生产者发消息（convertAndSend 的几种重载）

| 重载形式 | 走哪个交换机 | 什么时候用 |
|----------|--------------|-----------|
| `convertAndSend(routingKey, msg)` | 默认交换机 `""` | 最简单点对点 |
| `convertAndSend(exchange, routingKey, msg)` | 指定交换机 | **自定义交换机用这个** |
| `convertAndSend(exchange, routingKey, msg, postProcessor)` | 指定交换机 | 还要设置消息属性 |
| `convertAndSend(exchange, routingKey, msg, postProcessor, correlationData)` | 指定交换机 | 还要做发布确认（传标识） |

```java
rabbitTemplate.convertAndSend(
    RabbitConfig.SMS_EXCHANGE,      // 交换机名
    RabbitConfig.SMS_ROUTING_KEY,   // 路由键
    phone + ":" + code              // 消息体
);
```

> **`convert` 的含义**：`convertAndSend` 内部先用 `MessageConverter` 把你传的 `String`/对象序列化成 `Message` 字节，再 `send`。所以你能直接传 `String`，不用手动拼字节。

### 3. 消费者收消息（@RabbitListener）

```java
@Component
public class SmsListener {

    @RabbitListener(queues = RabbitConfig.SMS_QUEUE)  // 监听 code 队列
    public void handleSms(String message) {
        String[] parts = message.split(":");
        System.out.println("【模拟发短信】给 " + parts[0] + " 发送验证码：" + parts[1]);
    }
}
```

方法参数类型决定「收到消息的哪部分」：

| 参数写法 | 收到的内容 |
|----------|-----------|
| `String message` | 消息体（String 消息直接反序列化） |
| `byte[] body` | 原始字节数组 |
| `Message message` | 完整消息对象（body + properties） |
| `@Header("xxx") String h` | 指定的某个 header 属性 |
| `Channel channel` | 底层信道，用于手动 ack |

> **手动接收**（少用）：`rabbitTemplate.receiveAndConvert(queue)` 主动拉一条，队列空返回 `null`，不阻塞。

---

## 六、消息属性（MessageProperties）

### 1. 常用属性

一条消息 = **消息体（body）+ 消息属性（properties）**。属性是描述消息本身的元数据：

| 属性 | 含义 | 典型用途 |
|------|------|----------|
| `deliveryMode` | 1=非持久化，2=持久化 | 消息存磁盘，重启不丢 |
| `expiration` | 过期时间（**毫秒**） | 超时未消费自动删除，防堆积 |
| `contentType` | 内容类型（text/plain、application/json） | 消费者据此解析 |
| `messageId` | 消息唯一 ID | 追踪、幂等去重 |
| `headers` | 自定义键值对 | 附加业务信息 |
| `priority` | 优先级 | 配合优先级队列 |

### 2. 设置方式一：MessagePostProcessor（lambda）

```java
rabbitTemplate.convertAndSend(exchange, routingKey, msg, message -> {
    message.getMessageProperties().setExpiration("300000");          // 5 分钟过期
    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT); // 持久化
    return message;
});
```

### 3. 设置方式二：手动 new Message + send

```java
MessageProperties props = new MessageProperties();
props.setExpiration("300000");
props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

Message message = new Message("内容".getBytes(StandardCharsets.UTF_8), props);
rabbitTemplate.send(exchange, routingKey, message);  // 注意：是 send，不是 convertAndSend
```

> **区别**：`convertAndSend` = Spring 帮你「对象转字节」+ `send`；手动 `new Message` = 你自己做「对象转字节」这一步。两者最终都走 `send()`。

---

## 七、消息可靠性（三个层面）

保证消息「从生产到消费，任何一步都不丢」，分三个环节：

```
生产者 ──①发送──▶ 交换机 ──②存储──▶ 队列 ──③投递──▶ 消费者
```

| 环节 | 消息在哪丢 | 可靠性机制 | 关键配置 |
|------|-----------|-----------|----------|
| ① 发送者 | 发不到交换机 / 路由不到队列 | Publisher Confirm + Return | `publisher-confirm-type`、`publisher-returns` |
| ② 队列/交换机 | RabbitMQ 重启 | 持久化 | `durable=true`、`deliveryMode` |
| ③ 接收者 | 消费失败 / 宕机 | 手动 ack + 重试 | `acknowledge-mode: manual` |

### 1. 发送者可靠性：Confirm + Return

**问题**：默认发消息「发出去就不管」，交换机写错、路由失败都**静默丢消息**。

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated   # 发布确认（异步回调）
    publisher-returns: true              # 发布退回
    template:
      mandatory: true                    # 路由失败退回而不是丢弃
```

- **Confirm（确认）**：回答「消息到没到**交换机**」。每条消息带唯一编号（`deliveryTag`），到达后 RabbitMQ 回 `ack/nack`。
- **Return（退回）**：回答「消息到交换机了，但没路由进**队列**」。`mandatory=true` 时退回，否则丢弃。

**两个回调都是全局的**（set 在 RabbitTemplate 上），但 confirm 需要**每次发消息传 `CorrelationData`**：

```java
@Configuration
public class RabbitCallbackConfig {

    // ConfirmCallback：全局一个
    @Bean
    public RabbitTemplate.ConfirmCallback confirmCallback() {
        return (correlationData, ack, cause) -> {
            String id = correlationData == null ? "null" : correlationData.getId();
            if (ack) { /* 到交换机了 */ } else { /* 没到 */ }
        };
    }

    // ReturnsCallback：全局一个
    @Bean
    public RabbitTemplate.ReturnsCallback returnsCallback() {
        return returned -> { /* returned.getMessage() 是被退回的消息本体 */ };
    }
}
```

发消息时传 `CorrelationData`：

```java
rabbitTemplate.convertAndSend(exchange, routingKey, msg, null, new CorrelationData(phone));
```

> **为什么 return 全局、confirm 要每次传标识**：
> - **Return**：回调参数里**直接带着被退回的消息本体**（`ReturnedMessage`），天然能定位是哪条，所以全局一个即可。
> - **Confirm**：回调参数只有「编号 + ack 状态」，**没有消息内容**（确认回执很轻）。发 100 条消息收到一堆 ack，只能靠 `CorrelationData.getId()` 对上号，所以每次发消息要传一个。
> - 两个回调 Bean 由 Spring Boot 自动配置**自动 set 到 RabbitTemplate**，不需要自己 `new RabbitTemplate`。

### 2. 队列可靠性：持久化三件套

三个东西都要 `durable`，**缺一不可**：

| 对象 | 设置位置 | 作用 |
|------|----------|------|
| 交换机持久化 | 声明 Exchange 时 `durable=true` | 交换机结构存磁盘 |
| 队列持久化 | 声明 Queue 时 `durable=true` | 队列结构存磁盘 |
| 消息持久化 | 发消息时 `deliveryMode=PERSISTENT` | 消息内容存磁盘 |

> **关键提醒**：队列 `durable` 只保证「队列这个容器」重启还在；消息本身没设 `PERSISTENT`，重启后队列是空的。三个必须一起配。

### 3. 接收者可靠性：手动 ack + 重试

**问题**：默认**自动 ack**，消费者**一收到消息** RabbitMQ 就认为处理完、删除消息。处理到一半宕机，消息永久丢。

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: manual         # 手动确认
        retry:
          enabled: true                  # 消费失败先在本地重试
          max-attempts: 3                # 最多重试 3 次
```

```java
@RabbitListener(queues = RabbitConfig.SMS_QUEUE)
public void handleSms(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
    try {
        // 处理业务...
        channel.basicAck(tag, false);              // 成功 → 确认，消息删除
    } catch (Exception e) {
        channel.basicNack(tag, false, true);       // 失败 → 重新入队(requeue=true)
    }
}
```

- `basicAck(deliveryTag, multiple)`：告诉 RabbitMQ「这条处理完了，可以删」。
- `basicNack(deliveryTag, multiple, requeue)`：告诉 RabbitMQ「没处理成功」，`requeue=true` 让消息回到队首。

> **⚠ 最大的坑**：`requeue=true` 时，如果消息**本身就有问题**（如格式永远解析失败），会陷入「投递 → 失败 → requeue → 再投递」的**无限死循环**。解决办法：用 `retry.max-attempts` 限次，重试完还失败就 `requeue=false` 丢弃，或转投**死信队列（DLX）**。

---

## 八、常见问题排查

| 报错 | 含义 | 解决 |
|------|------|------|
| `ACCESS_REFUSED - Login was refused` | **认证失败**（登录阶段）：用户名不存在或密码错 | 检查 `username`/`password` 是否正确 |
| `ACCESS_REFUSED - access to vhost ... refused` | **授权失败**（登录后）：没权限访问该 vhost | 给用户授权到对应 vhost |
| `NOT_FOUND - no vhost` | vhost 没建 | 管理界面建 vhost |

> **区分认证 vs 授权**：`Login was refused` 是「登录阶段」被拒（用户名/密码错）；`access to vhost refused` 是「登录成功后」访问 vhost 被拒（没授权）。

**YAML 密码 0 开头必须加引号**：

```yaml
password: "060520"   # ✅ 加引号，按字符串解析
password: 060520     # ❌ 0 开头会被 YAML 当成数字，可能变 60520，导致认证失败
```

---

## 九、总结对照表

| 维度 | 核心内容 |
|------|----------|
| 核心模型 | 生产者 → 交换机（路由）→ 队列（存）→ 消费者 |
| 三个概念 | Exchange（路由）、Queue（存）、Binding（连线 + routing key） |
| 默认交换机 | 名字 `""`，routing key = 队列名，最简单点对点 |
| 使用三件套 | 声明 Bean、`convertAndSend` 发、`@RabbitListener` 收 |
| 消息属性 | `deliveryMode`（持久化）、`expiration`（过期）最常用 |
| 发送可靠 | Confirm（到交换机）+ Return（路由失败退回）+ CorrelationData |
| 队列可靠 | 交换机/队列 `durable` + 消息 `deliveryMode=PERSISTENT` |
| 接收可靠 | 手动 ack（`basicAck`/`basicNack`）+ 有限重试 |

> 做登录功能，先吃透「声明队列 → `convertAndSend` 发 → `@RabbitListener` 收」这条主线，验证码消息记得设 `expiration` 防堆积，可靠性三层按需逐层加。

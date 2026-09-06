# Redis 技术知识

> 本文档整理 Redis 在登录功能中的用法，涵盖依赖引入、序列化问题、自定义 RedisTemplate 配置、以及五种数据结构的操作方法。

## 一、Redis 是什么

Redis 是一个**基于内存的键值（key-value）存储数据库**，读写极快（微秒级），常用来做缓存、存 token、存验证码、分布式锁等。

在登录场景里，它的作用主要是：

- 存 JWT token / 黑名单（做登出、吊销）
- 存短信验证码、图形验证码（带过期时间，防重放）
- 存 Session（有状态登录方案时）

> 关键特点：**数据存在内存里，重启会丢**，所以通常只放「能丢的临时数据」，不放核心业务数据。数据可以设置**过期时间（TTL）**，到期自动删除。

---

## 二、需要引入的依赖

### 1. 核心依赖（必引）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**干什么**：Spring 官方的 Redis 启动器，帮你配好「连接、操作 Redis」的一整套东西。引入后能拿到两个核心工具：

| 工具 | 类型 | 用途 |
|------|------|------|
| `RedisTemplate` | 操作对象 | 最常用，往 Redis 里存/取数据（字符串、对象等） |
| `StringRedisTemplate` | 操作字符串 | 只存字符串，登录场景（存 token、验证码）基本够用 |

**内部做了什么**：
- 自动读取 `application.yml` 里的 `spring.data.redis.*` 配置，建好连接
- 默认用 **Lettuce** 作为 Redis 客户端（不是老项目常用的 Jedis），性能好、支持异步、线程安全

### 2. 可选依赖（按需加）

| 依赖 | 是否必须 | 一句话 |
|------|---------|--------|
| `spring-boot-starter-data-redis` | ✅ 必须 | 连接和操作 Redis 的核心 |
| `commons-pool2` | ⚠️ 配连接池才需要 | 连接池能力（Lettuce 开连接池必须有它，否则报错） |
| `spring-boot-starter-cache` | ❌ 进阶可选 | `@Cacheable` 等缓存注解 |
| `redisson` | ❌ 进阶可选 | 分布式锁等高级功能 |

### 3. 配置文件里加连接信息

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      # password: 你的密码（如果设置了）
      # database: 0
```

> 注意：本机要先装并启动 Redis，否则连不上。

---

## 三、序列化问题（默认 RedisTemplate 为什么不好用）

### 1. 什么是序列化

Redis 只能存**字节（bytes）**，不认识 Java 对象。

打个比方：Redis 是只能寄「包裹」的快递柜，而 `User` 对象是「活人」。要把活人塞进去，必须先**打包成盒子**（字节流），这叫**序列化（serialization）**；取出来时**拆盒还原**，叫**反序列化（deserialization）**。

所以 `RedisTemplate` 每次存数据前，必须先决定「用哪种方式打包」，这就是内部那一堆 `XXXSerializer` 的职责。

### 2. 默认的为什么不好用

Spring Boot 自动配的 `RedisTemplate`，默认用 **JDK 序列化**（`JdkSerializationRedisSerializer`），毛病很多：

| 问题 | 说明 |
|------|------|
| ① 对象必须实现 `Serializable` 接口 | 你的 `User` 类没实现这个接口，一存就报错 |
| ② 存进去是**二进制乱码** | redis-cli 里看是 `\xAC\xED\x00\x05t\x00\x04...`，人看不懂，没法排查 |
| ③ 体积大、速度慢 | 比 JSON 之类的格式臃肿 |
| ④ 只能 Java 自己读 | Python/Node 等其他系统读不了 |
| ⑤ 类结构一变就废 | 加了字段 `serialVersionUID` 变了，老数据反序列化报错 |
| ⑥ 有安全风险 | 反序列化不可信数据可能被攻击 |

**一句话**：JDK 序列化是给「Java 内部自己存自己」用的，不适合存给人看、跨系统、易排查的数据。

### 3. 自定义 RedisTemplate（标准写法）

登录场景希望：**key 存成可读字符串**（如 `login:token:123`），**value 存成 JSON**（可读、跨语言、体积小）。

```java
package com.wangguangyang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // 1. key 用字符串序列化：让 key 可读
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer); // hash 结构的 key 同理

        // 2. value 用 JSON 序列化：可读、跨语言、不要求 Serializable
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer); // hash 结构的 value 同理

        // 3. 初始化（把上面设的序列化器真正生效）
        template.afterPropertiesSet();
        return template;
    }
}
```

**为什么这么配**：
- **Key 用 `StringRedisSerializer`**：key 通常是有业务含义的字符串，存成字符串才可读、好管理（如按前缀 `login:*` 批量删）。
- **Value 用 `GenericJackson2JsonRedisSerializer`**：把对象转 JSON。它存的时候会额外记录一个类型字段 `@class`，取出来时能自动还原成正确类型，通用性最强。
- **`afterPropertiesSet()`**：模板创建完要「初始化」一下，把上面设的序列化器真正注册进去。

> **偷懒做法**：如果只存字符串（token、验证码本来就是字符串），直接用 `StringRedisTemplate` 即可，key 和 value 都是字符串，**完全不用自己配**。只有要存对象（如整个 `User`）才需要上面这套 JSON 配置。

### 4. Spring 如何选择用哪个对象（自动配置退让）

这是 Spring Boot 最核心的设计思想之一：**自动配置退让（auto-configuration back off）**。

1. Spring Boot 的自动配置类 `RedisAutoConfiguration` 里有：

```java
@Bean
@ConditionalOnMissingBean(name = "redisTemplate")   // ← 关键
public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory factory) {
    // ...默认的 JDK 序列化模板
}
```

2. `@ConditionalOnMissingBean(name = "redisTemplate")` 意思是：**「只有当容器里不存在叫 `redisTemplate` 的 Bean 时，我才创建默认的」**。

3. 你自己写了 `@Configuration` + `@Bean` 方法 `redisTemplate`，这个 Bean 会被 Spring **先注册**到容器里。

4. Spring 处理自动配置时检查条件，发现「已经有 `redisTemplate` 了」，于是自动配置的那个就不创建了，自动让路。

5. 最终容器里只有一个 `RedisTemplate` —— 就是你配的 JSON 版本。

**一句话总结**：Spring Boot 的默认配置都「留了后门」，只要你定义同名 Bean，它就自动退让、用你的。这也正是「约定大于配置」的体现。

---

## 四、五种数据结构及操作方法

`RedisTemplate` 方法很多，因为 Redis 有 **5 种核心数据结构**，每种用不同方法操作。统一套路：

```java
redisTemplate.opsForXxx().具体方法(...)
//            ↑拿到操作器    ↑在这个数据结构上干活
```

### 1. String —— `opsForValue()`（登录最常用 ⭐）

一个 key 对应一个值，值可以带**过期时间**。

```java
// 存 token，10 分钟后自动过期
redisTemplate.opsForValue().set("login:token:" + userId, token, 10, TimeUnit.MINUTES);

// 存验证码，5 分钟过期
redisTemplate.opsForValue().set("sms:code:" + phone, code, 5, TimeUnit.MINUTES);

// 取
String token = (String) redisTemplate.opsForValue().get("login:token:" + userId);

// 删除
redisTemplate.delete("login:token:" + userId);

// key 不存在才设置（防重复提交/幂等）
Boolean ok = redisTemplate.opsForValue().setIfAbsent("lock:" + id, "1", 30, TimeUnit.SECONDS);
```

**适用**：token、验证码、计数器、分布式锁。登录功能 90% 用这个。

### 2. Hash —— `opsForHash()`（存对象推荐 ⭐）

一个 key 下能存**多组「字段→值」**，适合把对象属性分开存。

```java
// 存一个用户对象（字段分散存）
redisTemplate.opsForHash().put("user:" + userId, "name", "张三");
redisTemplate.opsForHash().put("user:" + userId, "age", "25");

// 取某个字段
Object name = redisTemplate.opsForHash().get("user:" + userId, "name");

// 一次取全部字段，返回 Map
Map<Object, Object> userMap = redisTemplate.opsForHash().entries("user:" + userId);

// 判断某个字段是否存在
Boolean has = redisTemplate.opsForHash().hasKey("user:" + userId, "name");
```

**适用**：存对象、购物车。好处是能**单独改某个字段**，不用整个对象读写。

> 对比：`opsForValue` 存对象是把整个对象序列化成一段 JSON；`opsForHash` 是把对象拆成字段存。想整体读写的用前者，想按字段改的用后者。

### 3. List —— `opsForList()`（队列/栈）

有序、可重复，从两端进出。

```java
// 从左边（头部）塞
redisTemplate.opsForList().leftPush("op:log", "用户登录了");
// 从右边（尾部）塞
redisTemplate.opsForList().rightPush("op:log", "用户改密码了");

// 取一段（0 到 -1 表示全部）
List<Object> logs = redisTemplate.opsForList().range("op:log", 0, -1);

// 从右边弹出一个（队列：先进先出）
Object item = redisTemplate.opsForList().rightPop("op:log");

// 长度
Long size = redisTemplate.opsForList().size("op:log");
```

**适用**：消息队列、操作日志、时间线。

### 4. Set —— `opsForSet()`（去重）

无序、**不可重复**的集合。

```java
redisTemplate.opsForSet().add("online:users", userId1, userId2, userId3);

// 判断是否在集合里（如「今天已签到」）
Boolean isMember = redisTemplate.opsForSet().isMember("online:users", userId1);

// 取全部
Set<Object> all = redisTemplate.opsForSet().members("online:users");

// 交集（如「共同好友」）
Set<Object> common = redisTemplate.opsForSet().intersect("user1:friends", "user2:friends");
```

**适用**：去重（在线用户、已读列表、签到）、共同关注/好友。

### 5. ZSet —— `opsForZSet()`（有序集合，带分数）

和 Set 类似，但每个元素带一个**分数（score）**，能按分数排序。

```java
// 存排行榜：分数 = 积分
redisTemplate.opsForZSet().add("rank:score", "张三", 100);
redisTemplate.opsForZSet().add("rank:score", "李四", 200);

// 按分数从高到低取前 10 名
Set<Object> top10 = redisTemplate.opsForZSet().reverseRange("rank:score", 0, 9);

// 查某人的分数
Double score = redisTemplate.opsForZSet().score("rank:score", "张三");

// 查某人的排名（从 0 开始）
Long rank = redisTemplate.opsForZSet().reverseRank("rank:score", "张三");
```

**适用**：排行榜、优先级队列、滑动窗口限流。

---

## 五、总结对照表

| 数据结构 | 操作器 | 特点 | 登录/业务场景 |
|---------|--------|------|--------------|
| String | `opsForValue()` | 一个值 + 过期时间 | **token、验证码**（最常用） |
| Hash | `opsForHash()` | 字段→值 的映射 | 存用户对象、购物车 |
| List | `opsForList()` | 有序、可重复、两端进出 | 操作日志、消息队列 |
| Set | `opsForSet()` | 无序、去重 | 在线用户、签到、共同好友 |
| ZSet | `opsForZSet()` | 带分数、可排序 | 排行榜、限流 |

> 做登录功能，先吃透 `opsForValue()`（存 token、验证码，记得带过期时间），其他结构等遇到对应业务再学。

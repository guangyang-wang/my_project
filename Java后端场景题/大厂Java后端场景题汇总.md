# 大厂 Java 后端场景题汇总

> 面试核心考察的不是背答案，而是「**线上出问题 → 分层定位 → 拿证据 → 给可落地方案 → 讲权衡**」的完整思路。
> 每个场景题都按「**问题描述**（场景 + 为什么会出问题 + 面试官想问什么）→ **完美解决方案**（分步骤 + 代码 + 权衡）」来组织。

---

## 目录

1. [慢 SQL 如何排查与优化](#一慢-sql-如何排查与优化)
2. [缓存穿透 / 击穿 / 雪崩](#二缓存穿透--击穿--雪崩)
3. [高并发库存扣减（防超卖）](#三高并发库存扣减防超卖)
4. [秒杀系统设计](#四秒杀系统设计)
5. [分布式事务](#五分布式事务)
6. [幂等性设计](#六幂等性设计)
7. [线程池设计](#七线程池设计)
8. [线上问题排查（CPU 100% / GC / OOM）](#八线上问题排查cpu-100--gc--oom)

---

## 一、慢 SQL 如何排查与优化

### 问题描述

线上某接口响应时间突然从 50ms 飙到 5s，数据库连接池被打满，大量请求超时。业务方反馈「功能变卡了」。此时你需要定位到底是哪条 SQL 慢、为什么慢、怎么修。

**面试官真正想问的是：**
- 你有没有一套**系统化定位慢 SQL 的流程**，而不是只会说「加索引」。
- 你能否看懂执行计划（`EXPLAIN`），判断索引是否真正生效。
- 你知不知道「索引失效」的各种场景，以及「深分页」「回表」这类隐藏问题。
- 你有没有架构层的优化视野（缓存、分库分表、读写分离、ES）。

### 完美解决方案

#### 第 1 步：发现慢 SQL

```sql
-- 1. 开启慢查询日志（my.cnf / 动态设置）
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 1;          -- 阈值设为 1 秒
SET GLOBAL log_output = 'TABLE';          -- 记录到 mysql.slow_log 表，方便查询

-- 2. 查看慢 SQL
SELECT * FROM mysql.slow_log
WHERE query_time > 1
ORDER BY query_time DESC LIMIT 10;
```

> **阈值权衡**：设太小（如 0.01s）日志暴增占磁盘；设太大（默认 10s）会漏掉慢 SQL。一般生产设 **1 秒**，再配合 `log_queries_not_using_indexes` 记录未走索引的 SQL。

**辅助判断征兆：**
- **CPU 高** → SQL 里有大量计算、排序、聚合，或并发线程过多。
- **IO 高** → 全表扫描、没走索引、读取数据量过大。
- 结合 `SHOW PROCESSLIST` 看当前正在执行的 SQL 和执行时长：

```sql
SHOW FULL PROCESSLIST;  -- 找出 Command 为 Query 且 Time 很长的连接
```

#### 第 2 步：定位原因 —— EXPLAIN 看执行计划

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 123 AND status = 1 ORDER BY create_time DESC;
```

重点看三列：

| 字段 | 含义 | 好坏判断 |
|------|------|----------|
| `type` | 访问类型 | `ALL`(全表) < `index`(全索引) < `range`(范围) < `ref` < `eq_ref` < `const`(主键/唯一，最优) |
| `key` | 实际使用的索引 | 为 `NULL` 说明没命中索引 |
| `Extra` | 额外信息 | `Using filesort`(排序没走索引)、`Using temporary`(临时表) 要优化；`Using index`(覆盖索引) 最好 |

#### 第 3 步：常见慢 SQL 原因 + 对应优化

**① 索引失效（最高频）**

```sql
-- ❌ 左模糊查询，索引失效
SELECT * FROM user WHERE name LIKE '%张%';
-- ✅ 右模糊才走索引
SELECT * FROM user WHERE name LIKE '张%';

-- ❌ 索引列做运算/套函数
SELECT * FROM user WHERE YEAR(birthday) = 1999;
SELECT * FROM user WHERE age + 1 = 20;
-- ✅ 把运算移到等号右边
SELECT * FROM user WHERE birthday >= '1999-01-01' AND birthday < '2000-01-01';

-- ❌ 隐式类型转换（字符串列用数字比较）
SELECT * FROM user WHERE phone = 13800138000;   -- phone 是 varchar，会全表扫
-- ✅ 用字符串
SELECT * FROM user WHERE phone = '13800138000';

-- ❌ 违反最左前缀原则：联合索引 (a, b, c)
SELECT * FROM t WHERE b = 1 AND c = 2;           -- 跳过 a，索引失效
SELECT * FROM t WHERE a = 1 AND c = 2;           -- 用到 a，c 失效（b 断了）
-- ✅ 遵循最左前缀
SELECT * FROM t WHERE a = 1 AND b = 2 AND c = 3;

-- ❌ OR 连接了非索引列
SELECT * FROM t WHERE a = 1 OR b = 2;            -- 若 b 无索引，整体失效
-- ✅ 拆成两条再 UNION，或给 b 也建索引
```

**② 回表与覆盖索引**

```sql
-- ❌ SELECT * 即使命中索引，还要回表查全部列
SELECT * FROM orders WHERE user_id = 123;

-- ✅ 覆盖索引：查询列全部在联合索引里，不用回表（Extra 显示 Using index）
-- 建联合索引 (user_id, status, create_time)
SELECT user_id, status, create_time FROM orders WHERE user_id = 123 AND status = 1;
```

**③ 深分页（LIMIT 大偏移量）**

```sql
-- ❌ LIMIT 100000,20 会先扫 10 万行再丢弃，越来越慢
SELECT * FROM orders ORDER BY id LIMIT 100000, 20;

-- ✅ 方案一：覆盖索引 + 延迟关联（先定位主键，再回表取数据）
SELECT * FROM orders o
INNER JOIN (SELECT id FROM orders ORDER BY id LIMIT 100000, 20) t
ON o.id = t.id;

-- ✅ 方案二：游标分页（记录上一页最后一条的 id，用条件代替偏移量）
SELECT * FROM orders WHERE id > 100000 ORDER BY id LIMIT 20;
```

**④ 大事务 / 长事务**

- 事务内不做远程调用（RPC、HTTP）、不做循环查询，避免长时间持锁阻塞其他事务。
- 大事务拆小，尽早提交释放锁。

#### 第 4 步：SQL 本身没问题时，看架构层

- **读写分离**：主库写、从库读，把查询压力分散到从库。
- **分库分表**：按时间/冷热/用户 ID 分表，控制单表数据量。
- **Redis 缓存热点数据**：降低 DB 压力（注意缓存一致性，见后文）。
- **ES 检索引擎**：大字段检索、模糊匹配这类 SQL 硬伤，迁到 ES。
- **连接池配置**：检查 HikariCP `maximumPoolSize`、`connectionTimeout` 是否过小。

---

## 二、缓存穿透 / 击穿 / 雪崩

### 问题描述

**缓存穿透**：请求查询的数据，缓存和数据库里**都不存在**（如恶意请求 `id = -1` 或不存在商品）。因为查不到就不会写缓存，导致每次请求都直击数据库，大量恶意请求可直接把 DB 打挂。

**缓存击穿**：某个**热点 key**（如明星商品、热门新闻）在**过期的一瞬间**，海量并发请求同时打向数据库，瞬时把连接池打满、DB 崩溃。

**缓存雪崩**：大量 key 在**同一时间点集中过期**（如批量设置相同 TTL），或缓存服务（Redis）整体宕机，所有请求瞬间全部落到 DB，引发级联故障。

> **面试官想问**：能否准确区分这三个概念（很多人只背「三兄弟」但说不清区别），并给出对应且恰当的解决方案。

### 完美解决方案

#### 1. 缓存穿透 → 空值缓存 + 布隆过滤器

```java
// ✅ 空值缓存：不存在的 key 也缓存一个短 TTL 的空值，避免反复打 DB
public Object get(String key) {
    Object value = redis.get(key);
    if (value != null) {
        return value;                    // 命中缓存（含空值标记）
    }
    // 查库
    Object dbValue = db.query(key);
    if (dbValue == null) {
        redis.set(key, EMPTY_VALUE, 300, TimeUnit.SECONDS);  // 空值缓存 5 分钟
        return null;
    }
    redis.set(key, dbValue, 3600, TimeUnit.SECONDS);
    return dbValue;
}
```

```java
// ✅ 布隆过滤器：查询前先判断 key 是否可能存在，不存在的直接拦截
// 用 Redisson 的 RBloomFilter
RBloomFilter<String> filter = redisson.getBloomFilter("productId");
filter.tryInit(10000000L, 0.01);   // 预计 1kw 元素，误判率 1%

public Object get(String key) {
    if (!filter.contains(key)) {
        return null;                    // 一定不存在，直接拦截，不打 DB
    }
    // ... 正常缓存逻辑
}
```

> **权衡**：空值缓存实现简单但有内存占用；布隆过滤器有误判率（存在误判为存在，但不存在一定拦截），删除元素困难。生产常用**两者结合**。

#### 2. 缓存击穿 → 互斥锁 / 逻辑过期

```java
// ✅ 方案一：互斥锁（SETNX 分布式锁），只有一个线程回源加载，其他线程等待/重试
public String getWithLock(String key) {
    String value = redis.get(key);
    if (value != null) return value;

    // 加锁：SET key NX PX，保证只有一个线程能回源
    String lockKey = "lock:" + key;
    boolean locked = redis.setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
    if (locked) {
        try {
            // 双重检查，防止拿到锁后发现别人已加载好
            value = redis.get(key);
            if (value != null) return value;
            value = db.query(key);
            redis.set(key, value, 3600, TimeUnit.SECONDS);
        } finally {
            redis.delete(lockKey);       // 释放锁
        }
    } else {
        // 没拿到锁，睡一下重试
        Thread.sleep(50);
        return getWithLock(key);
    }
    return value;
}
```

```java
// ✅ 方案二：逻辑过期（缓存物理不设 TTL，value 里存逻辑过期时间）
// 过期后先返回旧数据，异步刷新，做到"零等待"，适合商品详情这类允许最终一致的场景
public String getWithLogicalExpire(String key) {
    CacheData data = redis.get(key);   // value 里包含 data + expireTime
    if (data == null) return null;
    if (data.expireTime > now()) {
        return data.value;              // 未过期，直接返回
    }
    // 已逻辑过期：抢锁成功后异步重建，当前请求仍返回旧值
    boolean locked = redis.setIfAbsent("lock:" + key, "1", 10, TimeUnit.SECONDS);
    if (locked) {
        threadPool.execute(() -> {      // 异步重建缓存
            String fresh = db.query(key);
            redis.set(key, new CacheData(fresh, now() + 3600));
            redis.delete("lock:" + key);
        });
    }
    return data.value;                  // 先返回旧数据，不阻塞
}
```

> **权衡**：互斥锁保证一致性但阻塞其他请求；逻辑过期不阻塞但有短暂的数据不一致。**热点商品**推荐逻辑过期（体验优先），**一致性要求高**的推荐互斥锁。

#### 3. 缓存雪崩 → 随机过期 + 多级缓存 + 限流

- **随机 TTL**：基础过期时间上叠加一个随机数，避免集中过期。
  ```java
  redis.set(key, value, 3600 + new Random().nextInt(600), TimeUnit.SECONDS);
  ```
- **多级缓存**：本地缓存（Caffeine/Guava）+ Redis + DB，层层兜底。
- **限流/熔断**：Redis 宕机时，用 Sentinel/熔断器对 DB 做保护，防止 DB 被瞬时打挂。
- **高可用**：Redis 主从 + 哨兵 / 集群，避免单点故障。

---

## 三、高并发库存扣减（防超卖）

### 问题描述

秒杀场景下，商品库存只剩 1 件，同时有 1000 个请求来抢。如果直接读库判断 `stock > 0` 再 `UPDATE`，会发生「多个线程都读到 stock=1，都认为有货，最终超卖」的经典并发问题。需要保证**不超卖、不重复下单、高并发**。

**面试官想问**：你是否理解「先查后改」在并发下的竞态问题，能否给出「Redis 预扣 + Lua 原子扣减 + DB 条件更新兜底」的完整方案。

### 完美解决方案

#### 第 1 步：Redis 预扣库存 + Lua 原子扣减（核心）

```lua
-- 扣减库存的 Lua 脚本：把「查库存 + 判重 + 扣减」放一起原子执行，防超卖防重复下单
-- KEYS[1] = 库存 key, KEYS[2] = 用户已抢标记 key
-- ARGV[1] = 扣减数量, ARGV[2] = 用户 ID
local stock = redis.call('GET', KEYS[1])
if not stock or tonumber(stock) < tonumber(ARGV[1]) then
    return -1                    -- 库存不足
end
-- 判重：一个用户只能抢一次
if redis.call('SISMEMBER', KEYS[2], ARGV[2]) == 1 then
    return -2                    -- 已抢过，防止重复下单
end
redis.call('DECRBY', KEYS[1], ARGV[1])
redis.call('SADD', KEYS[2], ARGV[2])
return 1                         -- 成功
```

```java
// Java 调用 Lua 脚本
Long result = redis.execute(script,
    List.of("stock:1001", "grabbed:1001"),   // KEYS
    "1", userId);                              // ARGV
if (result == -1) throw new BizException("库存不足");
if (result == -2) throw new BizException("重复下单");
```

#### 第 2 步：DB 条件更新兜底（双保险）

```sql
-- ✅ 条件更新：stock > 0 才扣减，天然防超卖，返回 1 才算成功
UPDATE stock SET stock = stock - 1 WHERE id = ? AND stock > 0;
```

```java
// MyBatis 判断返回值，返回 0 说明库存已耗尽
int rows = stockMapper.deduct(id);
if (rows == 0) throw new BizException("库存不足");
```

#### 第 3 步：异步落库 + 兜底

- Redis 扣减成功后发 MQ，订单服务异步消费创建正式订单。
- 订单超时未支付自动取消，**回滚库存**（`INCRBY` 加回 Redis）。
- **热点 key 优化**：库存分片（如 `stock:1001:0` ~ `stock:1001:9`），按用户 ID 哈希路由，降低单 key 并发压力。

> **权衡**：Redis 扣减性能高但 Redis 与 DB 存在短暂不一致，需要 MQ 保证最终一致 + 定时对账。

---

## 四、秒杀系统设计

### 问题描述

某电商大促，1 万件商品，10 万人同时抢购，瞬时 QPS 可能从几百飙到几十万。需要设计一个高并发秒杀系统，核心目标是：**不超卖、不重复下单、高可用、高性能、可追踪**。

**面试官想问**：你有没有**分层削峰**的系统思维，能否从「前端 → 网关 → 服务端 → Redis → MQ → DB」每一层说出拦截和优化手段。

### 完美解决方案（分层削峰）

#### 前端层
- 按钮防重复点击（置灰）。
- 验证码 / 滑块，防机器刷单。
- 活动开始前按钮不可用，开始后才启用。

#### 网关 / Nginx 层
- 按 IP、接口限流（令牌桶 / 漏桶算法）。
- 静态化秒杀页，CDN 缓存，减少后端压力。

#### 服务端
- **限流**：Guava `RateLimiter` 或 Sentinel，超出直接拒绝。
- **隔离**：秒杀接口用独立线程池 / 信号量隔离，防止拖垮其他接口。
- **降级**：预案包括直接关闭秒杀入口、返回静态化内容保护核心链路。

#### 核心链路（Redis + MQ + DB）

```
用户请求
  → 网关限流
    → 服务端限流（Sentinel）
      → Redis 预扣库存（Lua 原子扣减，见第三节）
        → 成功：发 MQ 异步创建订单
        → 失败：直接返回"已抢光/重复"
          → 订单服务消费 MQ 落库
            → 超时未支付 → 取消订单 → 回滚库存
```

#### 监控与兜底
- 实时监控 QPS、响应时间、错误率。
- 熔断机制快速失败，防止雪崩。
- 全链路日志（traceId），保证可追踪。

> **回答结构**：先讲整体架构（分层），再讲每层的具体手段，最后强调「削峰填谷 + 异步化 + 原子扣减」三个关键词。

---

## 五、分布式事务

### 问题描述

用户下单需要同时操作三个服务：**订单服务**（创建订单）、**库存服务**（扣库存）、**积分服务**（扣积分）。要求任一服务失败时，其他服务能保证数据一致（不能出现「订单创建了但库存没扣」或「钱扣了但订单没生成」）。这是典型的跨服务/跨库分布式事务问题。

**面试官想问**：你能否讲清 2PC/TCC/SAGA/MQ 最终一致几种方案的**区别、优缺点、适用场景**，并理解 **CAP 理论的权衡**。

### 完美解决方案

#### 方案对比

| 方案 | 一致性 | 实现 | 问题 |
|------|--------|------|------|
| **2PC / Seata AT** | 强一致 | 拦截 SQL 自动生成回滚日志 | 性能差、单点故障、全局锁导致 TPS 低（约 200），高并发阻塞严重 |
| **TCC**（Try-Confirm-Cancel） | 强一致 | 预留资源→确认/回滚两阶段 | 代码侵入性强，需自行实现三阶段逻辑 |
| **SAGA** | 最终一致 | 长事务拆为本地事务 + 补偿 | 需设计补偿事务，实现复杂 |
| **MQ 最终一致** | 最终一致 | 本地事务 + 消息队列 + 补偿 | 牺牲强一致性换高性能，需配套可靠性、幂等 |

#### 高频答案：基于 MQ 的最终一致性

**核心思想**：下单时先创建订单（本地事务），然后发消息到 MQ，库存服务、积分服务监听消息**异步处理**，通过「可靠投递 + 幂等 + 补偿」保证最终一致。

```java
// ✅ RocketMQ 事务消息（半消息）：本地事务与消息投递原子
public void createOrder(Order order) {
    transactionTemplate.execute(status -> {
        // 1. 本地事务：创建订单 + 写本地消息表（同库同事务）
        orderMapper.insert(order);
        localMsgMapper.insert(buildMsg(order));  // 本地消息表兜底
        // 2. 发送半消息，MQ 回查本地事务状态决定投递或回滚
        rocketMQTemplate.sendMessageInTransaction("order-topic", msg, order.getId());
        return null;
    });
}
```

**保证最终一致的三件套：**

1. **可靠投递**：生产者同步发送 + 失败重试；Kafka 开启副本持久化；消费者**手动确认**（`AckMode.MANUAL`），处理成功才提交 offset。
2. **本地消息表兜底**：订单创建时在同一事务里同时写订单表和消息表，定时任务扫描「未发送成功」的消息重新发送，保证消息不丢。
3. **补偿 + 死信队列**：消费失败超重试次数后进入死信队列，定时任务扫描并人工介入。

> **面试加分点**：明确说「极致性能下需要牺牲强一致性，但必须给出最终一致的补救方案」，体现对 CAP 的理解和工程权衡能力。

---

## 六、幂等性设计

### 问题描述

网络抖动导致客户端重试、MQ 消费重试、用户双击提交等，都可能导致**同一业务操作被执行多次**（如重复扣款、重复创建订单）。需要保证「同一个请求无论执行多少次，结果和执行一次一样」。

**面试官想问**：你是否理解幂等的本质是「**用唯一标识防止重复处理**」，能否给出数据库、Redis、Token 等多种实现。

### 完美解决方案

#### 1. 数据库唯一索引（最可靠）

```sql
-- 扣减流水表：order_id 加唯一索引
CREATE TABLE deduction_flow (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    amount DECIMAL(10,2),
    UNIQUE KEY uk_order_id (order_id)
);
```

```java
// 重复插入会抛 DuplicateKeyException，捕获即代表"已处理过"，跳过
try {
    flowMapper.insert(flow);
} catch (DuplicateKeyException e) {
    log.warn("重复请求，orderId={}", orderId);  // 天然防重
}
```

#### 2. Redis 去重（适合 MQ 消费）

```java
// 消费前用 Redis 记录已处理的消息 ID，重复消息直接忽略
public void consume(String msgId, String body) {
    // SETNX：返回 false 说明已处理过
    boolean first = redis.setIfAbsent("msg:" + msgId, "1", 1, TimeUnit.HOURS);
    if (!first) {
        return;   // 重复消息，忽略
    }
    // ... 处理业务
}
```

#### 3. 幂等 Token（防重复提交）

- 前端进入页面时，后端下发一个唯一 Token。
- 提交时携带 Token，后端用 Redis `SETNX` 校验 + 删除（`getAndDelete` 原子），已消费则拒绝重复提交。
- 可用 Lua 脚本保证「校验 + 删除」原子性。

#### 4. 业务层唯一键 + 状态机

- 订单用 `orderId` 做唯一键，配合状态机（如「待支付 → 已支付」），只有合法状态流转才执行，重复流转直接忽略。

> **权衡**：数据库唯一索引最可靠但有一定性能开销；Redis 去重性能高但需考虑 Redis 故障兜底。生产常「唯一索引兜底 + Redis 前置去重」结合。

---

## 七、线程池设计

### 问题描述

业务中频繁创建线程会带来极大的资源开销，需要设计一个线程池来复用线程、控制并发。但线程池参数配置不当会导致 OOM、任务堆积、CPU 打满等问题。面试官会问「你的线程池参数怎么定」「拒绝策略怎么选」。

**面试官想问**：你能否根据任务类型（CPU 密集 / IO 密集）合理推导线程数，理解队列和拒绝策略的选择。

### 完美解决方案

#### 参数推导

| 任务类型 | 核心线程数公式 | 说明 |
|----------|----------------|------|
| CPU 密集 | 核数 + 1 | 避免线程过多导致上下文切换开销 |
| IO 密集 | 核数 × (1 + 等待时间 / 计算时间) | 大部分时间在等 IO，可多开线程 |

```java
int cpuCores = Runtime.getRuntime().availableProcessors();

// CPU 密集型
ThreadPoolExecutor cpuPool = new ThreadPoolExecutor(
    cpuCores + 1, cpuCores + 1,
    0, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(1000),
    new ThreadPoolExecutor.CallerRunsPolicy());

// IO 密集型（等待/计算比约为 9，即 90% 时间在等 IO）
ThreadPoolExecutor ioPool = new ThreadPoolExecutor(
    cpuCores * 10, cpuCores * 10,
    60, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(10000),
    new ThreadPoolExecutor.CallerRunsPolicy());
```

#### 关键点

- **有界队列**：用 `ArrayBlockingQueue` 或指定容量的 `LinkedBlockingQueue`，避免无界队列在任务堆积时 OOM。
- **拒绝策略**：常用 `CallerRunsPolicy`（由调用线程执行，起到「减速」的背压作用，任务不直接丢失）。`AbortPolicy` 直接抛异常，`DiscardPolicy` 静默丢弃。
- **正确关闭**：
  - `shutdown()`：不再接受新任务，等待已提交任务执行完。
  - `shutdownNow()`：尝试中断正在执行的任务，返回未执行任务列表。
- **线程工厂**：自定义 `ThreadFactory` 给线程起有意义的名称，便于排查。
- **提交 vs 执行**：`execute()` 无返回值；`submit()` 返回 `Future`，可捕获异常和获取结果（注意 `Future.get()` 会阻塞，需设超时）。

> **加分点**：能提到「线程池 + 隔离」（不同业务用不同线程池，防止互相拖垮）、「`submit` 吞异常」的坑（`submit` 的异常被封装在 `Future` 里，不 `get()` 就不抛出）。

---

## 八、线上问题排查（CPU 100% / GC / OOM）

### 问题描述

线上服务 CPU 飙到 100%、接口卡死、频繁 Full GC、甚至 OOM 宕机。需要快速定位是哪个线程、哪段代码、哪个对象导致的。

**面试官想问**：你是否掌握 `top / jstack / jstat / jmap` 这一套 JVM 排查工具链的使用。

### 完美解决方案

#### 1. CPU 100% 排查

```bash
# ① 找进程：找到 CPU 占用最高的 Java 进程 PID
top

# ② 找线程：找到该进程内 CPU 占用最高的线程（TID）
top -Hp <pid>

# ③ 转十六进制：线程 ID 转成 16 进制（jstack 里是 16 进制表示）
printf '%x\n' <tid>

# ④ 打印线程栈：找到对应线程正在执行的代码
jstack <pid> | grep -A 20 <tid_hex>
```

> 常见结果：某个线程一直在跑某个业务方法（死循环 / 大量计算）→ 定位到代码。

#### 2. GC 频繁导致 CPU 高

```bash
# 查看 GC 统计：关注 FGC（Full GC 次数）、FGCT（Full GC 耗时）、各区使用率
jstat -gcutil <pid> 1000 10   # 每秒打印一次，共 10 次
```

- 若 `Eden` 区持续满、`FGC` 频繁 → 可能是**堆内存太小**或**对象创建过快**（内存泄漏）。
- 若 `Old` 区持续增长不回落 → 疑似**内存泄漏**。

#### 3. 内存泄漏 / OOM

```bash
# ① 堆转储（dump）
jmap -dump:format=b,file=heap.hprof <pid>

# ② 用 MAT / JProfiler 分析
# - Dominator Tree（支配树）：看哪个对象占内存最大
# - Histogram（直方图）：看哪类对象实例最多
# - Leak Suspects：自动分析疑似泄漏点
```

**常见 OOM 原因：**
- `OutOfMemoryError: Java heap space` → 堆内存不足，对象创建过多或泄漏。
- `OutOfMemoryError: Metaspace` → 加载的类太多（动态生成类、大量反射）。
- `OutOfMemoryError: unable to create new native thread` → 线程数超限（线程池未控制、线程未回收）。

**JVM 启动参数建议：**

```bash
java -Xms4g -Xmx4g \                          # 堆 4g，min=max 避免动态扩容
     -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m \
     -XX:+HeapDumpOnOutOfMemoryError \        # OOM 时自动 dump
     -XX:HeapDumpPath=/logs/heap.hprof \
     -XX:+PrintGCDetails -Xloggc:/logs/gc.log \  # 打印 GC 日志
     -jar app.jar
```

---

## 附：面试高分答题套路

遇到任何场景题，按这个结构回答，会比直接报答案高一个档次：

1. **定位**：先判断是应用层、中间件层（Redis/MQ）还是数据库层的问题，缩小范围。
2. **拿证据**：用日志 / 执行计划 / 线程栈 / 监控指标给出依据，而不是「我觉得」。
3. **给方案**：从 SQL、索引、连接池、缓存、分库分表、限流降级等维度给出可落地的手段。
4. **讲权衡**：说明一致性 vs 性能 vs 成本之间的取舍（如缓存击穿选互斥锁还是逻辑过期、分布式事务选强一致还是最终一致）。

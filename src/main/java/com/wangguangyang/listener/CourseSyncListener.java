package com.wangguangyang.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wangguangyang.config.RabbitConfig;
import com.wangguangyang.dto.CanalFlatMessage;
import com.wangguangyang.entity.CourseDoc;
import com.wangguangyang.repository.CourseDocRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 课程同步消费者（Canal → RabbitMQ → ES）
 *
 * 是什么：监听 Canal 发到 RabbitMQ 的 binlog 变更消息，把 MySQL 里 course 表的变化同步到 ES。
 * 干什么：收到一条消息（一次 INSERT/UPDATE/DELETE），解析出操作类型和行数据，
 *         按规则写 ES（新增/更新 upsert、删除 deleteById），替代原来 Service 里的手动双写。
 * 为什么：
 *   - 解决双写不一致：binlog 是 MySQL 提交后的「权威顺序」，Canal 订阅 binlog 再异步同步 ES，
 *     MySQL 行锁+事务保证的顺序通过 binlog 传导出来，不会再出现「并发下旧数据覆盖新数据」的丢失更新；
 *   - 解耦主流程：写 MySQL 和写 ES 分离，ES 挂了不影响主流程，靠消息堆积 + 幂等 + 对账兜底。
 *
 * 顺序保证：@RabbitListener 默认单消费者串行消费（concurrency=1），
 *           同一队列里的消息按先进先出处理，保证同一行数据的变更按 binlog 顺序落到 ES。
 * 幂等保证：save 按 id upsert（同 id 覆盖）、deleteById 删不存在的 id 也不报错，
 *           所以即使消息重复消费，结果也是幂等的。
 */
@Slf4j
@Component
public class CourseSyncListener {

    @Autowired
    private CourseDocRepository courseDocRepository;

    /**
     * 专用 ObjectMapper：配置 SNAKE_CASE 命名策略，把「下划线列名」自动映射到「驼峰字段」。
     *
     * 为什么单独 new 一个、不用 Spring 注入的全局 ObjectMapper：
     *   - Canal 消息里 data 的 key 是数据库列名（course_no、selected_count），
     *     而 CourseDoc 字段是驼峰（courseNo、selectedCount），需要 snake_case 转换；
     *   - Spring 的全局 ObjectMapper 还要负责序列化 Result 等响应（那里字段就是驼峰），
     *     改它的命名策略会污染全局，所以这里用一个独立的、只服务 Canal 解析的 ObjectMapper。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * 消费 Canal 消息
     *
     * 参数用 String 而非 byte[]：Canal 发到 RabbitMQ 的消息 contentType 是 application/json，
     * 消息体就是 JSON 字符串，Spring 已经把它转成 String 放进 Message 的 payload。
     * 若这里声明 byte[]，Spring 的 StringToArrayConverter 会把整段 JSON 按逗号拆开、逐个 token 转 byte，
     * 结果对 "{\"data\":[...]}" 做 Byte.parseByte 直接抛 NumberFormatException。
     * 所以直接收 String，交给下面的 ObjectMapper 反序列化。
     */
    @RabbitListener(queues = RabbitConfig.CANAL_QUEUE)
    public void onCanalMessage(String json) {
        CanalFlatMessage msg;

        try {
            msg = objectMapper.readValue(json, CanalFlatMessage.class);
            log.info("接受的消息为{}",msg);
        } catch (Exception e) {
            // 解析失败不能往外抛：一抛，Spring 默认会 requeue（重新投递），
            // 坏消息会永远卡在队列里反复重试。所以记日志后直接丢弃（或可转死信队列）。
            log.error("解析 Canal 消息失败，已丢弃。原始消息: {}", json, e);
            return;
        }

        // 1. 跳过 DDL：建表/改表没有行数据，也不需要同步
        if (Boolean.TRUE.equals(msg.getIsDdl())) {
            return;
        }
        // 2. 只处理 course 表：Canal 的 filter.regex=my_project\..* 会把所有表的变更都发过来
        //    （user、course_time、enrollment 等），ES 只存 course，其余表忽略。
        if (!"course".equals(msg.getTable())) {
            return;
        }

        // 3. 按操作类型分发（先判 null：switch 对 null 会抛 NPE，导致消息被 requeue 无限重试）
        String type = msg.getType();
        if (type == null) {
            log.warn("Canal 消息缺少 type 字段，已丢弃: {}", json);
            return;
        }
        switch (type) {
            case "INSERT" -> handleInsert(msg);
            case "UPDATE" -> handleUpdate(msg);
            case "DELETE" -> handleDelete(msg);
            default -> log.debug("忽略不处理的 Canal 消息类型: {}", type);
        }
    }

    /**
     * 处理 INSERT：把新行 upsert 进 ES
     */
    private void handleInsert(CanalFlatMessage msg) {
        List<Map<String, Object>> rows = msg.getData();
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            courseDocRepository.save(toCourseDoc(row));
        }
    }

    /**
     * 处理 UPDATE：区分「普通更新」和「逻辑删除」
     *
     * 关键点：Course 用了 @TableLogic，deleteById 在 binlog 里不是 DELETE 而是
     *         UPDATE course SET deleted=1 WHERE id=?。所以这里要判断：
     *         - deleted=1 → 逻辑删除 → 删 ES 文档（否则被删的课还能被搜到）；
     *         - 其他 → 普通更新 → upsert 覆盖 ES 里旧字段。
     */
    private void handleUpdate(CanalFlatMessage msg) {
        List<Map<String, Object>> rows = msg.getData();
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            // Canal 把列值都序列化成字符串，所以用 String.valueOf 统一比较，兼容 "1" 和 1
            if ("1".equals(String.valueOf(row.get("deleted")))) {
                courseDocRepository.deleteById(toLong(row.get("id")));
            } else {
                courseDocRepository.save(toCourseDoc(row));
            }
        }
    }

    /**
     * 处理 DELETE：物理删除兜底，从 old 里拿被删行的 id 删 ES 文档
     *
     * 为什么从 old 拿 id：DELETE 时 data 为空（行已删），old 里是被删那行的旧数据（含 id）。
     */
    private void handleDelete(CanalFlatMessage msg) {
        List<Map<String, Object>> rows = msg.getOld();
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            courseDocRepository.deleteById(toLong(row.get("id")));
        }
    }

    /**
     * 把「列名 → 列值」的 Map 转成 CourseDoc
     *
     * 为什么用 convertValue 而不是手写 set：
     *   - objectMapper 配了 SNAKE_CASE，会把 course_no → courseNo、selected_count → selectedCount 自动对应；
     *   - convertValue 会把字符串值（Canal 序列化后全是字符串）按字段类型转好：
     *     "1" → Long、"0" → Integer、"3.0" → BigDecimal，不用逐个手动转型；
     *   - Map 里 CourseDoc 没有的列（hours、campus、create_time、deleted 等）靠 CourseDoc 类上的
     *     @JsonIgnoreProperties(ignoreUnknown = true) 忽略，不报错。
     */
    private CourseDoc toCourseDoc(Map<String, Object> row) {
        return objectMapper.convertValue(row, CourseDoc.class);
    }

    /**
     * 安全地把列值转成 Long（Canal 序列化后是字符串 "1"，也可能是数字 1）
     */
    private Long toLong(Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }
}

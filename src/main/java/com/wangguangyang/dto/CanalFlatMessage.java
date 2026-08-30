package com.wangguangyang.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Canal FlatMessage 消息结构
 *
 * 是什么：Canal 用「flatMessage 模式」时，把一条 binlog 变更序列化成的 JSON 结构，本类是其反序列化载体。
 * 干什么：消费端拿到 Canal 发到 RabbitMQ 的 JSON 字符串后，用 Jackson 反序列化成这个对象，
 *         从里面读 type（操作类型）、table（表名）、data（变更后的行）、old（变更前的行）。
 * 为什么：Canal 1.1.x 的 flatMessage 是事实标准格式，字段名固定（data/old/type/table/isDdl），
 *         定义一个类比用 Map 硬解析更清晰、类型安全。
 *
 * 只声明消费端用得到的字段（Canal 消息里还有 es/gtid/mysqlType/sql/sqlType/pkNames/ts 等用不到的字段，
 * 靠类上的 @JsonIgnoreProperties(ignoreUnknown = true) 忽略——Jackson 默认遇到未知字段会抛
 * UnrecognizedPropertyException，必须显式关掉）：
 *   - type：操作类型，INSERT / UPDATE / DELETE
 *   - database：库名（本项目的 my_project）
 *   - table：表名（只关心 course）
 *   - isDdl：是不是 DDL 语句（建表/改表），DDL 没有行数据，要跳过
 *   - data：变更【后】的行数据（INSERT 的新行 / UPDATE 后的新值），每行是一个「列名 → 列值」的 Map
 *   - old：变更【前】的行数据（UPDATE 前的旧值 / DELETE 删掉的行），INSERT 时是 null
 *
 * 注意 data/old 里的 key 是「数据库列名」（下划线格式，如 course_no、selected_count），
 * 不是 Java 驼峰字段，映射到 CourseDoc 时需要转换（见 CourseSyncListener）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanalFlatMessage {

    /** 操作类型：INSERT / UPDATE / DELETE */
    private String type;

    /** 数据库名，如 my_project */
    private String database;

    /** 表名，如 course / course_time / enrollment */
    private String table;

    /** 是否为 DDL 语句（建表、改表等），DDL 没有行数据需跳过 */
    private Boolean isDdl;

    /** 变更后的行数据：List 是因为一条 SQL 可能批量改多行；每行是「列名 → 列值」 */
    private List<Map<String, Object>> data;

    /** 变更前的行数据：UPDATE 的旧值、DELETE 被删的行；INSERT 时为 null */
    private List<Map<String, Object>> old;
}

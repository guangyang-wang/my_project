package com.wangguangyang.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.math.BigDecimal;

/**
 * 课程 ES 文档类（精简字段）
 *
 * 是什么：对应 ES 里 course 索引的「文档」结构，一条文档 = 一门课的搜索/展示信息。
 * 干什么：把 MySQL 里需要被搜索、被列表展示的课程字段，单独抽成这个类写进 ES。
 * 为什么：
 *   - ES 只存「搜索 + 列表展示」要用的字段（精简），点详情再回 MySQL 查完整字段，
 *     避免双份全量数据、也避免把 deleted/createTime 这类无搜索意义的字段塞进 ES；
 *   - 单独建类（而不是在 Course 上加 @Document）：MyBatis 的 @TableName/@TableLogic
 *     和 ES 的 @Document/@Field 是两套职责不同的注解，混在一个类里难维护。
 *
 * 字段分词策略：
 *   - Keyword（不分词）：courseNo、term —— 精确匹配（编号、学期要精确筛选）
 *   - Text + ik_max_word（索引时最细切词）/ ik_smart（搜索时智能切词）：
 *     courseName、teacherName、college、major —— 中文分词搜索
 *   - Integer/Double：category、credit、capacity、selectedCount、status —— 数值，用于过滤和展示
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)  // Canal 行数据里 hours/campus/create_time 等字段本类不存，忽略以免反序列化报错
@Document(indexName = "course")
@Setting(shards = 1, replicas = 0)  // 单机开发：1 分片 0 副本，避免单节点副本无法分配导致集群一直黄色
public class CourseDoc {

    /** 文档主键，和 MySQL 的 course.id 保持一致（双写时 upsert 才能按 id 覆盖） */
    @Id
    private Long id;

    /** 课程编号（精确匹配，如 CS101） */
    @Field(type = FieldType.Keyword)
    private String courseNo;

    /** 课程名称（中文分词搜索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String courseName;

    /** 课程类别：1=必修 2=选修 3=公选 4=实践（数值过滤） */
    @Field(type = FieldType.Integer)
    private Integer category;

    /** 学分（展示用） */
    @Field(type = FieldType.Double)
    private BigDecimal credit;

    /** 任课教师（中文分词搜索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String teacherName;

    /** 开课学院（中文分词搜索，如搜"计算机"能匹配"计算机学院"） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String college;

    /** 开课专业（中文分词搜索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String major;

    /** 开课学期（精确匹配，如 2026-2027-1） */
    @Field(type = FieldType.Keyword)
    private String term;

    /** 选课人数上限（展示/过滤） */
    @Field(type = FieldType.Integer)
    private Integer capacity;

    /** 已选人数（展示） */
    @Field(type = FieldType.Integer)
    private Integer selectedCount;

    /** 状态：0=未开放 1=可选 2=已满 3=已结束 4=下架（过滤） */
    @Field(type = FieldType.Integer)
    private Integer status;
}

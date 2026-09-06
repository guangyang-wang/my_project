package com.wangguangyang.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 修改课程请求参数
 *
 * 是什么：前端（管理员）修改课程时传过来的请求体，继承 CourseAddDTO。
 * 干什么：Controller 用 @RequestBody 接收，Service 拿它「先删后插」更新 course + course_time。
 * 为什么新增和修改要分开建 DTO：
 *   - 新增没有 id（主键自增生成），修改必须带 id（定位要改的那一行）；
 *   - 唯一性/冲突校验规则不同：修改要「排除自身」，新增不用，分开后 Service 不用到处 if 判断；
 *   - 两者需求会各自演进，分开互不影响。
 *   但字段本身和新增几乎一样，所以用「继承」复用字段定义，避免 20 个字段重复写两遍。
 *
 * 说明：@EqualsAndHashCode(callSuper = true) 让 equals/hashCode 把父类字段也算进去，
 *       消除 Lombok 继承时的告警（@Data 默认生成的 equals 不包含父类字段）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CourseUpdateDTO extends CourseAddDTO {

    /** 课程ID（主键，必传，用于定位要更新的行） */
    private Long id;
}

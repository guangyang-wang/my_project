package com.wangguangyang.repository;

import com.wangguangyang.entity.CourseDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 课程 ES 仓库
 *
 * 是什么：Spring Data Elasticsearch 的 Repository 接口，类似 MyBatis-Plus 的 BaseMapper。
 * 干什么：继承即得到 save（新增/按 id 覆盖）、findById、deleteById、count 等 CRUD 方法，
 *         以及后续可声明的方法名查询（如 findByCourseNameContaining）。
 * 为什么：和 BaseMapper 一样，Spring Data 靠「接口 + 方法名约定」在运行时自动生成实现，
 *         不用自己写 SQL 或查询代码。
 */
public interface CourseDocRepository extends ElasticsearchRepository<CourseDoc, Long> {
}

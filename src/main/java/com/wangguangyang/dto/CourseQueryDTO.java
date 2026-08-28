package com.wangguangyang.dto;

import lombok.Data;

/**
 * 课程查询请求参数
 *
 * 是什么：前端传过来的课程查询条件对象。
 * 干什么：Controller 用 GET 接收，Spring 自动把 ?courseNo=xxx&courseName=yyy&pageNum=1&pageSize=10
 *         绑定到这个对象，Service 拿它拼查询条件。
 * 为什么：用对象接收比一个个 @RequestParam 清晰，也方便以后加查询字段（如类别、学期）。
 *
 * 查询字段说明（「或」体现在：两个字段都可空，可单独用、可组合用）：
 *   - 传 courseNo：按课程编号精确查（eq，编号是业务唯一码）
 *   - 传 courseName：按课程名称模糊查（like）
 *   - 两个都传：AND（编号 + 名称同时满足）
 *   - 都不传：查全部（分页）
 */
@Data
public class CourseQueryDTO {

    /** 课程编号（可空，精确匹配） */
    private String courseNo;

    /** 课程名称（可空，模糊匹配） */
    private String courseName;

    /** 页码（默认第 1 页） */
    private Integer pageNum = 1;

    /** 每页条数（默认 10 条） */
    private Integer pageSize = 10;
}

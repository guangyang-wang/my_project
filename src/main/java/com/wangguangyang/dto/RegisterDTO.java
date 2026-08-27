package com.wangguangyang.dto;

import lombok.Data;

/**
 * 注册请求参数
 *
 * 是什么：前端传过来的注册请求体。
 * 干什么：Controller 用 @RequestBody 接收，Service 拿它做注册。
 * 为什么：用对象接收比一个个 @RequestParam 清晰，也方便 swagger 文档展示字段。
 *
 * 字段约束：
 *   - 学号：4位年份 + 30 + 4位任意数字，如 2024302803，且不能重复
 *   - 姓名、密码、身份证、学院、专业、班级：必填
 *   - 性别：1=男 2=女，必填
 *   - 入学年份：如 2024，必填
 */
@Data
public class RegisterDTO {

    /** 学号 */
    private String studentNo;

    /** 姓名 */
    private String name;

    /** 密码（后端会用 BCrypt 加密后存库） */
    private String password;

    /** 身份证号 */
    private String idCard;

    /** 性别：1=男 2=女 */
    private Integer gender;

    /*电话号码*/
    private String phone;

    /** 学院 */
    private String college;

    /** 专业 */
    private String major;

    /** 班级 */
    private String className;

    /** 入学年份 */
    private Integer enrollmentYear;
}

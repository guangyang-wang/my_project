package com.wangguangyang.dto;

import lombok.Data;

/**
 * 学号密码登录请求参数
 *
 * 是什么：前端传过来的登录请求体。
 * 干什么：Controller 用 @RequestBody 接收，Service 拿它做登录。
 * 为什么：用对象接收比一个个 @RequestParam 清晰，也方便 swagger 文档展示字段。
 */
@Data
public class StudentLoginDTO {

    /** 学号 */
    private String studentNo;

    /** 密码 */
    private String password;
}

package com.wangguangyang.dto;

import lombok.Data;

/**
 * 手机号验证码登录请求参数
 *
 * 是什么：前端传过来的登录请求体。
 * 干什么：Controller 用 @RequestBody 接收，Service 拿它做登录。
 * 为什么：用对象接收比一个个 @RequestParam 清晰，也方便 swagger 文档展示字段。
 */
@Data
public class PhoneLoginDTO {

    /** 手机号 */
    private String phone;

    /** 验证码 */
    private String code;
}

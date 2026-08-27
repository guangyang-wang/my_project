package com.wangguangyang.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息
 *
 * 是什么：登录成功后、或拦截器解析 token 后，装「当前是谁在访问」的轻量对象。
 * 干什么：拦截器解析出用户信息后存进 ThreadLocal，Controller 里取出来用。
 * 为什么：不能把整个 User 实体(含密码、身份证等敏感字段)塞进 ThreadLocal，只用这几个必要字段即可。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    /** 用户 id */
    private Long id;

    /** 学号 */
    private String studentNo;

    /** 姓名 */
    private String name;
}

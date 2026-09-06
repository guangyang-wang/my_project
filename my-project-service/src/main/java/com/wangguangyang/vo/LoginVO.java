package com.wangguangyang.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功返回的对象
 *
 * 是什么：登录接口成功后的返回体。
 * 干什么：把 token + 用户基本信息一起给前端。
 * 为什么：前端拿到 token 用于后续请求的 Authorization 头，同时拿到 name 用于页面显示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /** JWT token */
    private String token;

    /** 用户基本信息 */
    private LoginUser user;
}

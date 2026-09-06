package com.wangguangyang.common;

import com.wangguangyang.vo.LoginUser;

/**
 * 当前登录用户的 ThreadLocal 工具类
 *
 * 是什么：用 ThreadLocal 在「同一次请求」内传递当前登录用户。
 * 干什么：拦截器解析 token 后 set 进去，Controller 用 get 取出来，请求结束 remove 清理。
 * 为什么：
 *   - 拦截器和 Controller 是不同类，需要一种方式把「当前用户」传给 Controller，ThreadLocal 是标准做法。
 *   - ThreadLocal 是线程隔离的，每个请求一个线程，互不干扰。
 *   - 用完必须 remove，否则线程复用(如 Tomcat 线程池)时会串数据、甚至内存泄漏。
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 设置当前登录用户 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 获取当前登录用户 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 清理（请求结束后必须调用，防止线程复用时串数据） */
    public static void remove() {
        HOLDER.remove();
    }
}

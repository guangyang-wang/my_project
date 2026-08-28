package com.wangguangyang.common;

/**
 * 业务异常
 *
 * 是什么：自定义的运行时异常，专门表示「业务上预期内的错误」（如学号不存在、密码错误、验证码错误）。
 * 干什么：业务代码里 throw new BusinessException("提示信息")，由全局异常处理器精确捕获并返回给前端。
 * 为什么：
 *   - 和裸的 RuntimeException 区分开，全局异常处理器能精确匹配到它，把 message 当提示返回给用户；
 *   - 而空指针、SQL 异常这类「非预期」异常则走兜底处理，返回笼统提示，不把内部细节暴露给前端。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}

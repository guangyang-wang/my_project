package com.wangguangyang.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 *
 * 是什么：用 @RestControllerAdvice 标注的类，统一接管所有 Controller 抛出的异常。
 * 干什么：把异常转换成统一的 Result 结构返回给前端，替代每个 Controller 里手写的 try-catch。
 * 为什么：
 *   - Controller 里 try-catch 重复且容易漏，集中在这里一处配置全局生效；
 *   - 区分「业务异常」和「系统异常」：业务异常把提示给用户看，系统异常给笼统提示 + 记日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常
     *
     * 业务代码主动 throw new BusinessException("学号不存在") 这类，message 是给用户看的具体提示，
     * 直接原样返回即可，不需要记日志（这是预期内的正常分支）。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Object> handleBusinessException(BusinessException e) {
        return Result.error(e.getMessage());
    }

    /**
     * 兜底处理其它所有异常（空指针、SQL 异常、类型转换等）
     *
     * 这些是程序 bug 或意外情况，不能让用户看到堆栈细节，返回笼统提示；
     * 真实原因通过日志打印完整堆栈，方便开发排查。
     */
    @ExceptionHandler(Exception.class)
    public Result<Object> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统繁忙，请稍后重试");
    }
}

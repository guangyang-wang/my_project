package com.wangguangyang.common;

/**
 * 统一返回结果（网关裁剪版）
 *
 * 是什么：从原单体复制的统一响应体。
 * 干什么：网关鉴权失败返回 401 时，用统一的 JSON 格式（{code, message, data}）响应前端。
 * 为什么裁剪掉 @Schema 注解：原类用了 knife4j 的 swagger 注解，但网关不引 knife4j，
 *   留着会编译失败，网关也不需要接口文档，所以去掉。
 */
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public Result(String message, T data, Integer code) {
        this.message = message;
        this.data = data;
        this.code = code;
    }

    public static <T> Result<T> success() {
        return new Result<>("成功", null, 200);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>("成功", data, 200);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(message, null, 500);
    }

    /** 带自定义状态码的错误（网关 401 用） */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(message, null, code);
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}

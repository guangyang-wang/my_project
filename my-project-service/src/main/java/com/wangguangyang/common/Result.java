package com.wangguangyang.common;

import io.swagger.v3.oas.annotations.media.Schema;

public class Result<T>{
    @Schema(description = "状态码：200 成功，其他为失败")
    private Integer code;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "返回的数据")
    private T data;

    public Result(String message, T data, Integer code) {
        this.message = message;
        this.data = data;
        this.code = code;
    }

    public static<T> Result<T> success(){
        return new Result("成功",null,200);
    }

    public static<T> Result<T>success(T data){
        return new Result<T>("成功",data,200);
    }

    public static<T> Result<T> error(String message){
        return new Result(message,null,500);
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

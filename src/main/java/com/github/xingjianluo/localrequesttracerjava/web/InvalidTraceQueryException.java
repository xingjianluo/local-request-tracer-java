package com.github.xingjianluo.localrequesttracerjava.web;

/**
 * 查询接口的非法入参（枚举值、时间范围、游标无法解析）。
 * 单独成类，避免与业务代码抛出的 IllegalArgumentException 混用到同一异常出口。
 */
public class InvalidTraceQueryException extends RuntimeException {

    public InvalidTraceQueryException(String message) {
        super(message);
    }

    public InvalidTraceQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}

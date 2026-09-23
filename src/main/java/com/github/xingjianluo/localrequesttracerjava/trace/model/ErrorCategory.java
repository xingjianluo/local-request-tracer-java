package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 归一化后的稳定错误类别。
 * 每个类别固定 HTTP 状态码与对外安全文案，不随入口变化，也不携带内部堆栈。
 */
public enum ErrorCategory {
    VALIDATION_ERROR(400, "请求参数不合法"),
    UNAUTHENTICATED(401, "身份未认证"),
    FORBIDDEN(403, "禁止访问该资源"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源状态冲突"),
    RATE_LIMITED(429, "请求过于频繁，请稍后再试"),
    UPSTREAM_FAILURE(502, "下游服务调用失败"),
    TIMEOUT(504, "下游服务调用超时"),
    INTERNAL_ERROR(500, "服务内部错误"),
    UNKNOWN(500, "未知错误");

    private final int httpStatus;
    private final String safeMessage;

    ErrorCategory(int httpStatus, String safeMessage) {
        this.httpStatus = httpStatus;
        this.safeMessage = safeMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String safeMessage() {
        return safeMessage;
    }
}

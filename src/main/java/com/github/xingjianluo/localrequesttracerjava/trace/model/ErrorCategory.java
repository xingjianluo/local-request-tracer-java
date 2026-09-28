package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 归一化后的稳定错误类别。
 * 每个类别固定 HTTP 状态码与对外安全文案，不随入口变化，也不携带内部堆栈。
 *
 * <p>{@code priority} 用于一条链路上多个 span 失败时确定“最终归类”：
 * 优先级高者胜出，同类并列时再按 span 开始时间、spanId 兜底，
 * 结论只取决于最终快照内容，与同步/异步入口、包装路径无关，可稳定复现。
 */
public enum ErrorCategory {
    TIMEOUT(100, 504, "下游服务调用超时"),
    UPSTREAM_FAILURE(90, 502, "下游服务调用失败"),
    RATE_LIMITED(80, 429, "请求过于频繁，请稍后再试"),
    CONFLICT(70, 409, "资源状态冲突"),
    FORBIDDEN(60, 403, "禁止访问该资源"),
    UNAUTHENTICATED(50, 401, "身份未认证"),
    NOT_FOUND(40, 404, "资源不存在"),
    VALIDATION_ERROR(30, 400, "请求参数不合法"),
    INTERNAL_ERROR(20, 500, "服务内部错误"),
    UNKNOWN(10, 500, "未知错误");

    private final int priority;
    private final int httpStatus;
    private final String safeMessage;

    ErrorCategory(int priority, int httpStatus, String safeMessage) {
        this.priority = priority;
        this.httpStatus = httpStatus;
        this.safeMessage = safeMessage;
    }

    public int priority() {
        return priority;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String safeMessage() {
        return safeMessage;
    }
}

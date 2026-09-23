package com.github.xingjianluo.localrequesttracerjava.web;

/**
 * 对外统一错误体：只含稳定类别、安全文案与追踪标识，
 * 不包含异常类名、消息或堆栈。
 */
public record ApiError(String category, String message, String traceId) {
}

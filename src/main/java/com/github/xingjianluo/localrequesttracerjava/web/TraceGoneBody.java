package com.github.xingjianluo.localrequesttracerjava.web;

/**
 * 追踪曾经存在但已被回收时的响应体：标识、回收原因（EXPIRED/EVICTED）与安全文案。
 */
public record TraceGoneBody(String traceId, String reason, String message) {
}

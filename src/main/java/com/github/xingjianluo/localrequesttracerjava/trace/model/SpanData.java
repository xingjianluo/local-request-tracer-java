package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 单个操作（span）结束后产生的不可变快照。
 * errorMessage 只允许放 {@link ErrorCategory} 的安全文案，禁止放原始异常堆栈。
 */
public record SpanData(
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        long startEpochMs,
        long durationMs,
        String threadName,
        SpanStatus status,
        ErrorCategory errorCategory,
        String errorMessage,
        LatencyClass latencyClass
) {
}

package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 一条追踪链的一层归因汇总（从不可变快照派生，本身不可变）。
 * status：链路整体结论；errorCategory：失败时的最终归一化类别（成功为 null）；
 * slowestSpanName / slowestSpanDurationMs：最耗时环节的名字与毫秒数。
 */
public record TraceAttribution(
        SpanStatus status,
        ErrorCategory errorCategory,
        String slowestSpanName,
        long slowestSpanDurationMs
) {
}

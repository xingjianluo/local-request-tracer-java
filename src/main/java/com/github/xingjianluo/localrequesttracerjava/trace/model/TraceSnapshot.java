package com.github.xingjianluo.localrequesttracerjava.trace.model;

import java.util.List;

/**
 * 一条追踪链完成后的不可变聚合快照，供查询接口使用。
 * 构造时做防御式拷贝，保证外部读到的永远是完整记录。
 */
public record TraceSnapshot(
        String traceId,
        long startEpochMs,
        long endEpochMs,
        long totalDurationMs,
        SpanStatus status,
        List<SpanData> spans
) {
    public TraceSnapshot {
        spans = List.copyOf(spans);
    }
}

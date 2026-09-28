package com.github.xingjianluo.localrequesttracerjava.trace.model;

import java.util.List;

/**
 * 一条追踪链完成后的不可变聚合快照，供查询接口使用。
 * 构造时做防御式拷贝，保证外部读到的永远是完整记录。
 *
 * <p>{@code attribution} 是整链一层归因（最终失败类别 + 最慢一环），
 * 构造快照时一次性算好，列表与详情口径完全一致。
 */
public record TraceSnapshot(
        String traceId,
        long startEpochMs,
        long endEpochMs,
        long totalDurationMs,
        SpanStatus status,
        List<SpanData> spans,
        TraceAttribution attribution
) {
    public TraceSnapshot {
        spans = List.copyOf(spans);
        if (attribution == null) {
            attribution = TraceAttribution.of(spans);
        }
    }

    /** 兼容旧构造方式：归因由 span 列表自动计算。 */
    public TraceSnapshot(String traceId, long startEpochMs, long endEpochMs, long totalDurationMs,
                         SpanStatus status, List<SpanData> spans) {
        this(traceId, startEpochMs, endEpochMs, totalDurationMs, status, spans, null);
    }
}

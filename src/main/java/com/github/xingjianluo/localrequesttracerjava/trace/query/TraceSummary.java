package com.github.xingjianluo.localrequesttracerjava.trace.query;

import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceAttribution;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;

/**
 * 列表场景的轻量条目：标识、起止与耗时、整体结论 + 一层归因，不含 span 明细。
 */
public record TraceSummary(
        String traceId,
        long startEpochMs,
        long endEpochMs,
        long totalDurationMs,
        SpanStatus status,
        TraceAttribution attribution
) {
    public static TraceSummary from(TraceSnapshot snapshot) {
        return new TraceSummary(
                snapshot.traceId(),
                snapshot.startEpochMs(),
                snapshot.endEpochMs(),
                snapshot.totalDurationMs(),
                snapshot.status(),
                snapshot.attribution()
        );
    }
}

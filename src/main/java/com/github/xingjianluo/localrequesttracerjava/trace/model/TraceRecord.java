package com.github.xingjianluo.localrequesttracerjava.trace.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一条追踪链在写入期间的聚合对象。
 *
 * <p>并发约束：
 * <ul>
 *   <li>span 列表用 CopyOnWriteArrayList，异步线程追加与查询线程读取互不阻塞，
 *       任何时刻读到的都是完整对象，不存在半截记录；</li>
 *   <li>查询侧只暴露 {@link #snapshot()} 产出的不可变快照，
 *       快照中的 span 列表在拷贝点定型，后续追加不影响已发出的快照。</li>
 * </ul>
 */
public final class TraceRecord {

    private final String traceId;
    private final long startEpochMs;
    private final CopyOnWriteArrayList<SpanData> spans = new CopyOnWriteArrayList<>();
    private volatile long endEpochMs;
    private volatile SpanStatus status = SpanStatus.SUCCESS;

    public TraceRecord(String traceId, long startEpochMs) {
        this.traceId = traceId;
        this.startEpochMs = startEpochMs;
    }

    public String traceId() {
        return traceId;
    }

    public long startEpochMs() {
        return startEpochMs;
    }

    public void addSpan(SpanData span) {
        spans.add(span);
        if (span.status() == SpanStatus.FAILED) {
            status = SpanStatus.FAILED;
        }
    }

    public void complete(long endEpochMs, SpanStatus finalStatus) {
        if (finalStatus == SpanStatus.FAILED) {
            status = SpanStatus.FAILED;
        }
        this.endEpochMs = endEpochMs;
    }

    public boolean isComplete() {
        return endEpochMs > 0;
    }

    public TraceSnapshot snapshot() {
        List<SpanData> copy = List.copyOf(spans);
        long end = endEpochMs;
        long total = end > 0 ? end - startEpochMs : -1;
        return new TraceSnapshot(traceId, startEpochMs, end, total, status, copy);
    }
}

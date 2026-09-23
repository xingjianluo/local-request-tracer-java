package com.github.xingjianluo.localrequesttracerjava.trace;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一条完整追踪链：traceId + 归属于它的全部 span。
 * SpanRecord 为不可变对象，单次 add 原子可见，不会出现半截记录。
 */
public final class TraceRecord {

    private final String traceId;
    private final CopyOnWriteArrayList<SpanRecord> spans = new CopyOnWriteArrayList<>();

    public TraceRecord(String traceId) {
        this.traceId = traceId;
    }

    public String getTraceId() { return traceId; }

    /** 追加一个 span（线程安全，单个不可变对象整体入列）。 */
    public void addSpan(SpanRecord span) {
        spans.add(span);
    }

    /** 当前已记录的 span 快照（不可变副本）。 */
    public List<SpanRecord> getSpans() {
        return List.copyOf(spans);
    }
}

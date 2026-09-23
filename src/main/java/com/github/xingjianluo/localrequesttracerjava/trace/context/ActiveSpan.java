package com.github.xingjianluo.localrequesttracerjava.trace.context;

import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.LatencyClass;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进行中的 span 句柄。只在所属线程通过 {@link TraceContext} 访问，
 * 结束后由 Tracer 生成不可变的 {@link SpanData}。
 * 失败标记只记录归一化类别与安全文案，不保留原始异常。
 */
public final class ActiveSpan {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final String kind;
    private final String threadName;
    private final long startEpochMs;
    private final long startNano;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    private volatile SpanStatus status = SpanStatus.SUCCESS;
    private volatile ErrorCategory errorCategory;
    private volatile String errorMessage;

    public ActiveSpan(String traceId, String spanId, String parentSpanId,
               String name, String kind, long startEpochMs) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.kind = kind;
        this.startEpochMs = startEpochMs;
        this.startNano = System.nanoTime();
        this.threadName = Thread.currentThread().getName();
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public String name() {
        return name;
    }

    public boolean isEnded() {
        return ended.get();
    }

    public SpanStatus status() {
        return status;
    }

    public long startNano() {
        return startNano;
    }

    /** 标记失败：只接受归一化类别与安全文案。重复标记时保留首次结论。 */
    public void markFailed(ErrorCategory category, String safeMessage) {
        if (category == null) {
            return;
        }
        // 首次失败结论为准，避免后续覆盖导致归因漂移
        if (this.errorCategory == null) {
            this.errorCategory = category;
            this.errorMessage = safeMessage;
            this.status = SpanStatus.FAILED;
        }
    }

    public boolean compareAndSetEnd() {
        return ended.compareAndSet(false, true);
    }

    /** 生成不可变快照；durationMs 与延迟归因由 Tracer 统一计算。 */
    public SpanData toSpanData(long durationMs, LatencyClass latencyClass) {
        return new SpanData(
                spanId,
                parentSpanId,
                name,
                kind,
                startEpochMs,
                durationMs,
                threadName,
                status,
                errorCategory,
                errorMessage,
                latencyClass
        );
    }
}

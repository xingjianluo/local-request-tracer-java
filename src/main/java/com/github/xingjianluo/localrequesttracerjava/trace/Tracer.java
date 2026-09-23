package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.context.ActiveSpan;
import com.github.xingjianluo.localrequesttracerjava.trace.context.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.model.LatencyClass;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 追踪门面：开启/结束 span 与 trace，结束时把不可变快照写入有界存储。
 *
 * <p>归属关系全部通过 {@link TraceContext} 维护；跨线程传播由
 * {@link TraceContext#capture()} / {@link TraceContext#attach} 完成，
 * 异步子操作与父请求共享同一 traceId。
 *
 * <p>结束语义：trace 关闭时一次性生成 {@link TraceSnapshot} 并落库，
 * 落库对象不可变，保证查询侧永远读不到半截记录。
 */
public final class Tracer {

    private static final Logger log = LoggerFactory.getLogger(Tracer.class);

    /** 结束当前 trace 的句柄，必须在开启线程关闭。 */
    public interface TraceHandle extends AutoCloseable {
        String traceId();

        @Override
        void close();
    }

    private final TraceStore store;
    private final ErrorClassifier classifier;
    private final long slowThresholdMs;
    private final Map<String, TraceRecord> activeRecords = new ConcurrentHashMap<>();

    public Tracer(TraceStore store, ErrorClassifier classifier, long slowThresholdMs) {
        this.store = store;
        this.classifier = classifier;
        this.slowThresholdMs = slowThresholdMs;
    }

    /** 开启一条新追踪链，同时开启根 span。 */
    public TraceHandle beginTrace(String name, String kind) {
        String traceId = newTraceId();
        TraceContext.clear();
        TraceContext.beginTrace(traceId);
        TraceRecord record = new TraceRecord(traceId, System.currentTimeMillis());
        activeRecords.put(traceId, record);
        ActiveSpan root = startSpan(name, kind);
        return new TraceHandleImpl(traceId, root);
    }

    /**
     * 在当前上下文下开启子 span。
     * 无追踪上下文时返回 null（静默降级，不阻断业务）；
     * 异步线程的首个 span 以提交时刻所在 span 为父，保证归属不丢。
     */
    public ActiveSpan startSpan(String name, String kind) {
        String traceId = TraceContext.currentTraceId();
        if (traceId == null) {
            return null;
        }
        ActiveSpan parent = TraceContext.currentSpan();
        String parentSpanId;
        if (parent != null) {
            parentSpanId = parent.spanId();
        } else {
            parentSpanId = TraceContext.asyncParentSpanId();
            TraceContext.consumeAsyncParent();
        }
        ActiveSpan span = new ActiveSpan(traceId, newSpanId(), parentSpanId,
                name, kind, System.currentTimeMillis());
        TraceContext.push(span);
        return span;
    }

    /** 结束 span：计算耗时与延迟归因，写入所属 trace 的聚合记录。 */
    public void endSpan(ActiveSpan span) {
        if (span == null || !span.compareAndSetEnd()) {
            return;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - span.startNano());
        LatencyClass latency = durationMs >= slowThresholdMs ? LatencyClass.SLOW : LatencyClass.NORMAL;
        SpanData data = span.toSpanData(durationMs, latency);
        TraceContext.pop(span);
        TraceRecord record = activeRecords.get(span.traceId());
        if (record != null) {
            record.addSpan(data);
        }
        if (latency == LatencyClass.SLOW) {
            log.info("slow span traceId={} span={} kind={} durationMs={} thresholdMs={}",
                    span.traceId(), span.name(), data.kind(), durationMs, slowThresholdMs);
        }
    }

    /** 把当前 span 标记为失败：异常先归一化，只记录类别与安全文案。 */
    public void markCurrentFailed(Throwable t) {
        ActiveSpan current = TraceContext.currentSpan();
        if (current != null) {
            current.markFailed(classifier.classify(t), classifier.safeMessage(t));
        }
    }

    public Optional<TraceSnapshot> find(String traceId) {
        return store.find(traceId);
    }

    /** 同步子操作模板：自动开/关 span，异常时归因后原样抛出。 */
    public <T> T call(String name, String kind, Supplier<T> action) {
        ActiveSpan span = startSpan(name, kind);
        try {
            return action.get();
        } catch (RuntimeException | Error e) {
            if (span != null) {
                span.markFailed(classifier.classify(e), classifier.safeMessage(e));
            }
            throw e;
        } finally {
            endSpan(span);
        }
    }

    public void run(String name, String kind, Runnable action) {
        call(name, kind, () -> {
            action.run();
            return null;
        });
    }

    private void endTrace(TraceHandleImpl handle) {
        ActiveSpan root = handle.root;
        if (root != null && !root.isEnded()) {
            endSpan(root);
        }
        TraceRecord record = activeRecords.remove(handle.traceId);
        if (record != null) {
            record.complete(System.currentTimeMillis(), record.snapshot().status());
            store.save(record.snapshot());
        }
        TraceContext.clear();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private final class TraceHandleImpl implements TraceHandle {
        private final String traceId;
        private final ActiveSpan root;
        private volatile boolean closed;

        TraceHandleImpl(String traceId, ActiveSpan root) {
            this.traceId = traceId;
            this.root = root;
        }

        @Override
        public String traceId() {
            return traceId;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            endTrace(this);
        }
    }
}

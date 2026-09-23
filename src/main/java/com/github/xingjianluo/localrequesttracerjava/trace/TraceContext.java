package com.github.xingjianluo.localrequesttracerjava.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 ThreadLocal 的追踪上下文。
 * 每个线程持有自己的 traceId 与 span 栈；跨线程通过不可变快照传播，
 * 并发请求之间互不可见，杜绝串链。
 */
public final class TraceContext {

    /** 单个 span 的进行中状态（仅归属创建它的线程）。 */
    private record Frame(String spanId, String parentSpanId, String operation,
                         String threadName, long startEpochMillis, long startNanos) {
    }

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<Deque<Frame>> SPAN_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    /** 由 Spring 配置注入；无上下文时所有操作静默降级为 no-op，不拖垮正常请求。 */
    private static volatile TraceStore store;
    private static volatile long slowThresholdNanos = TimeUnit.MILLISECONDS.toNanos(500);

    private TraceContext() {
    }

    public static void configure(TraceStore traceStore, long slowThresholdMillis) {
        store = traceStore;
        slowThresholdNanos = TimeUnit.MILLISECONDS.toNanos(slowThresholdMillis);
    }

    /** 开启一条新追踪，返回 traceId。 */
    public static String startTrace() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        TRACE_ID.set(traceId);
        SPAN_STACK.get().clear();
        TraceStore s = store;
        if (s != null) {
            s.getOrCreate(traceId);
        }
        return traceId;
    }

    /** 结束当前线程的追踪并清理上下文。 */
    public static void endTrace() {
        TRACE_ID.remove();
        SPAN_STACK.remove();
    }

    /** 当前线程的 traceId，无上下文时返回 null。 */
    public static String currentTraceId() {
        return TRACE_ID.get();
    }

    /** 在指定操作上开启子 span，返回 spanId；无追踪上下文时返回 null。 */
    public static String startSpan(String operation) {
        String traceId = TRACE_ID.get();
        if (traceId == null) {
            return null;
        }
        Deque<Frame> stack = SPAN_STACK.get();
        Frame parent = stack.peek();
        Frame frame = new Frame(
                UUID.randomUUID().toString().replace("-", ""),
                parent == null ? null : parent.spanId(),
                operation,
                Thread.currentThread().getName(),
                System.currentTimeMillis(),
                System.nanoTime());
        stack.push(frame);
        return frame.spanId();
    }

    /** 结束当前 span 并记录（成功路径）。 */
    public static void endSpan() {
        endSpan(null);
    }

    /** 结束当前 span 并记录（异常路径，异常会被归一化分类）。 */
    public static void endSpan(Throwable error) {
        String traceId = TRACE_ID.get();
        if (traceId == null) {
            return;
        }
        Deque<Frame> stack = SPAN_STACK.get();
        Frame frame = stack.poll();
        if (frame == null) {
            return;
        }
        long duration = System.nanoTime() - frame.startNanos();
        SpanRecord span = new SpanRecord(
                frame.spanId(),
                frame.parentSpanId(),
                frame.operation(),
                frame.threadName(),
                frame.startEpochMillis(),
                duration,
                error != null,
                duration >= slowThresholdNanos,
                ErrorClassifier.classify(error));
        TraceStore s = store;
        if (s != null) {
            s.getOrCreate(traceId).addSpan(span);
        }
    }

    /** 抓取当前上下文快照，用于异步传播；无上下文时返回 null。 */
    public static TraceContextSnapshot snapshot() {
        String traceId = TRACE_ID.get();
        if (traceId == null) {
            return null;
        }
        return new TraceContextSnapshot(traceId, List.copyOf(SPAN_STACK.get()));
    }

    /** 在目标线程安装快照，返回可恢复的句柄；快照为 null 时返回空句柄。 */
    public static Scope install(TraceContextSnapshot snapshot) {
        String prevTraceId = TRACE_ID.get();
        Deque<Frame> prevStack = SPAN_STACK.get();
        Deque<Frame> prevCopy = new ArrayDeque<>(prevStack);
        if (snapshot != null) {
            TRACE_ID.set(snapshot.traceId());
            // 快照列表是栈的自顶向下拷贝，还原时逆序压栈。
            Deque<Frame> restored = new ArrayDeque<>();
            List<Frame> frames = snapshot.frames();
            for (int i = frames.size() - 1; i >= 0; i--) {
                restored.push(frames.get(i));
            }
            SPAN_STACK.set(restored);
        }
        return new Scope(prevTraceId, prevCopy);
    }

    /** 上下文快照（不可变）。 */
    public static final class TraceContextSnapshot {
        private final String traceId;
        private final List<Frame> frames;

        private TraceContextSnapshot(String traceId, List<Frame> frames) {
            this.traceId = traceId;
            this.frames = new ArrayList<>(frames);
        }

        public String traceId() {
            return traceId;
        }

        private List<Frame> frames() {
            return frames;
        }
    }

    /** 安装句柄，close 后恢复先前上下文。 */
    public static final class Scope implements AutoCloseable {
        private final String prevTraceId;
        private final Deque<Frame> prevStack;
        private boolean closed;

        private Scope(String prevTraceId, Deque<Frame> prevStack) {
            this.prevTraceId = prevTraceId;
            this.prevStack = prevStack;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (prevTraceId == null) {
                TRACE_ID.remove();
            } else {
                TRACE_ID.set(prevTraceId);
            }
            SPAN_STACK.set(prevStack);
        }
    }
}

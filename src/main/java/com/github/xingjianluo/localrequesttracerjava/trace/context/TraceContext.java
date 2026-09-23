package com.github.xingjianluo.localrequesttracerjava.trace.context;

/**
 * 基于 ThreadLocal 的追踪上下文持有器。
 *
 * <p>传播规则：
 * <ul>
 *   <li>同一线程内通过栈维护 span 父子关系；</li>
 *   <li>跨线程时由提交方 {@link #capture()}，执行方 {@link #attach(ContextSnapshot)}，
 *       执行完毕关闭 {@link Scope} 恢复执行线程原状；</li>
 *   <li>请求结束必须 {@link #clear()}，防止线程复用导致串链。</li>
 * </ul>
 */
public final class TraceContext {

    /** 跨线程传播的最小快照：traceId + 提交时刻所在 span。 */
    public record ContextSnapshot(String traceId, String spanId) {
        public static ContextSnapshot none() {
            return new ContextSnapshot(null, null);
        }

        public boolean isPresent() {
            return traceId != null;
        }
    }

    /** 附件恢复句柄，close 时恢复为 attach 前的状态（通常是移除）。 */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<ThreadState> STATE = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 捕获当前线程上下文；无追踪时返回 {@link ContextSnapshot#none()}。 */
    public static ContextSnapshot capture() {
        ThreadState state = STATE.get();
        if (state == null) {
            return ContextSnapshot.none();
        }
        ActiveSpan current = state.stack.peek();
        return new ContextSnapshot(state.traceId, current == null ? null : current.spanId());
    }

    /**
     * 在执行线程安装捕获到的上下文。返回的 {@link Scope} 关闭后移除本次安装，
     * 保证线程池线程不会被上一次任务污染。
     */
    public static Scope attach(ContextSnapshot snapshot) {
        if (snapshot == null || !snapshot.isPresent()) {
            return () -> {
            };
        }
        ThreadState state = new ThreadState(snapshot.traceId());
        state.asyncParentSpanId = snapshot.spanId();
        STATE.set(state);
        return STATE::remove;
    }

    public static String currentTraceId() {
        ThreadState state = STATE.get();
        return state == null ? null : state.traceId;
    }

    public static ActiveSpan currentSpan() {
        ThreadState state = STATE.get();
        return state == null ? null : state.stack.peek();
    }

    /** 开启新 trace 时调用；若已有上下文则先清理，避免串链。 */
    public static ThreadState beginTrace(String traceId) {
        ThreadState state = new ThreadState(traceId);
        STATE.set(state);
        return state;
    }

    public static void push(ActiveSpan span) {
        ThreadState state = STATE.get();
        if (state == null) {
            return;
        }
        state.stack.push(span);
    }

    /**
     * 弹出指定 span。若栈顶不是 expected（异常路径乱序结束），
     * 则从栈中移除该 span，保证栈最终一致，不抛出异常影响业务。
     */
    public static void pop(ActiveSpan expected) {
        ThreadState state = STATE.get();
        if (state == null) {
            return;
        }
        if (state.stack.peek() == expected) {
            state.stack.pop();
        } else {
            state.stack.remove(expected);
        }
    }

    /** 异步线程首 span 的父节点：提交时刻所在 span。 */
    public static String asyncParentSpanId() {
        ThreadState state = STATE.get();
        return state == null ? null : state.asyncParentSpanId;
    }

    /** 消费异步父节点：首个 span 建立后即失效，后续 span 走正常栈父子关系。 */
    public static void consumeAsyncParent() {
        ThreadState state = STATE.get();
        if (state != null) {
            state.asyncParentSpanId = null;
        }
    }

    public static void clear() {
        STATE.remove();
    }
}

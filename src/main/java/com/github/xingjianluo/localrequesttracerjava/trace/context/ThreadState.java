package com.github.xingjianluo.localrequesttracerjava.trace.context;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单个线程上的追踪上下文：所属 traceId 与未结束 span 栈。
 * asyncParentSpanId 用于异步线程：父 span 可能已在提交线程结束，
 * 新线程的第一个 span 以它作为父节点，保证归属关系不丢。
 */
public final class ThreadState {

    final String traceId;
    final Deque<ActiveSpan> stack = new ArrayDeque<>();
    volatile String asyncParentSpanId;

    ThreadState(String traceId) {
        this.traceId = traceId;
    }
}

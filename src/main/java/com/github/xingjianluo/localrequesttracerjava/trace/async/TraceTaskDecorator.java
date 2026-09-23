package com.github.xingjianluo.localrequesttracerjava.trace.async;

import com.github.xingjianluo.localrequesttracerjava.trace.context.TraceContext;
import org.springframework.core.task.TaskDecorator;

/**
 * 任务装饰器：在提交线程 capture 上下文，在执行线程 attach，
 * 使异步子操作归属同一条追踪链；执行完毕恢复执行线程原状。
 */
public final class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TraceContext.ContextSnapshot snapshot = TraceContext.capture();
        return () -> {
            try (TraceContext.Scope ignored = TraceContext.attach(snapshot)) {
                runnable.run();
            }
        };
    }
}

package com.github.xingjianluo.localrequesttracerjava.trace;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 提供携带追踪上下文的执行器包装，保证异步子操作归属同一条追踪。
 * 快照在提交线程抓取，在执行线程安装与还原，执行线程自身的上下文不被污染。
 */
public final class TraceExecutors {

    private TraceExecutors() {
    }

    public static Executor tracePropagating(Executor delegate) {
        return command -> delegate.execute(wrap(command));
    }

    public static ExecutorService tracePropagating(ExecutorService delegate) {
        return new TracePropagatingExecutorService(delegate);
    }

    public static Runnable wrap(Runnable runnable) {
        TraceContext.TraceContextSnapshot snapshot = TraceContext.snapshot();
        return () -> {
            try (TraceContext.Scope ignored = TraceContext.install(snapshot)) {
                runnable.run();
            }
        };
    }

    private static final class TracePropagatingExecutorService
            extends java.util.concurrent.AbstractExecutorService {
        private final ExecutorService delegate;

        private TracePropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(wrap(command));
        }

        @Override
        public void shutdown() { delegate.shutdown(); }

        @Override
        public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }

        @Override
        public boolean isShutdown() { return delegate.isShutdown(); }

        @Override
        public boolean isTerminated() { return delegate.isTerminated(); }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}

package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.InMemoryTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.SpanRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceExecutors;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨线程/异步执行的子操作必须归属同一条追踪，且父子关系正确。
 */
class TraceExecutorsTest {

    private static final Logger log = LoggerFactory.getLogger(TraceExecutorsTest.class);

    private TraceStore store;
    private ExecutorService rawPool;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore(64);
        TraceContext.configure(store, 500);
        rawPool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        TraceContext.endTrace();
        rawPool.shutdownNow();
    }

    @Test
    void asyncChildrenBelongToSameTrace() throws InterruptedException {
        ExecutorService executor = TraceExecutors.tracePropagating(rawPool);
        String traceId = TraceContext.startTrace();
        String parentSpanId = TraceContext.startSpan("parent");

        int children = 6;
        CountDownLatch done = new CountDownLatch(children);
        Map<String, String> childTraceIds = new ConcurrentHashMap<>();
        for (int i = 0; i < children; i++) {
            final int idx = i;
            executor.submit(() -> {
                // 判定依据：异步线程内看到的 traceId 与提交线程一致
                childTraceIds.put("child-" + idx, TraceContext.currentTraceId());
                TraceContext.startSpan("async-child-" + idx);
                TraceContext.endSpan();
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        TraceContext.endSpan();
        TraceContext.endTrace();

        log.info("traceId={} childTraceIds={} 判定依据=所有子线程 traceId 与父一致", traceId, childTraceIds);
        assertEquals(children, childTraceIds.size());
        childTraceIds.forEach((k, v) -> assertEquals(traceId, v, k + " attached to wrong trace"));

        TraceRecord record = store.find(traceId).orElseThrow();
        List<SpanRecord> spans = record.getSpans();
        assertEquals(children + 1, spans.size(), "expected parent + children spans");

        Map<String, SpanRecord> byOp = spans.stream()
                .collect(Collectors.toMap(SpanRecord::getOperation, Function.identity()));
        for (int i = 0; i < children; i++) {
            SpanRecord child = byOp.get("async-child-" + i);
            assertNotNull(child, "missing child span " + i);
            // 判定依据：子 span 的 parentSpanId 必须等于父 spanId
            log.info("traceId={} op={} parentSpanId={} 判定依据=parentSpanId==父spanId({})",
                    traceId, child.getOperation(), child.getParentSpanId(), parentSpanId);
            assertEquals(parentSpanId, child.getParentSpanId());
        }
    }

    @Test
    void executorThreadContextIsRestoredAfterTask() throws InterruptedException {
        ExecutorService executor = TraceExecutors.tracePropagating(rawPool);
        String traceId = TraceContext.startTrace();
        TraceContext.endTrace(); // 提交前主线程已清理，验证任务结束后线程不残留

        CountDownLatch done = new CountDownLatch(2);
        String[] residual = new String[2];
        executor.submit(() -> {
            // 该任务在无上下文时提交，snapshot 为 null，执行后不应残留
            residual[0] = TraceContext.currentTraceId();
            done.countDown();
        });
        // 先在线程内建立上下文再清理，提交一个带快照的任务，之后同一线程复用时不得残留
        TraceContext.startTrace();
        executor.submit(() -> {
            assertNotNull(TraceContext.currentTraceId());
            done.countDown();
        });
        TraceContext.endTrace();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        log.info("traceId={} residualInBareTask={} 判定依据=无快照任务中 traceId 为 null", traceId, residual[0]);
        assertNull(residual[0], "bare task must not see any trace context");
    }
}

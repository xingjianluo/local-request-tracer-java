package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.InMemoryTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.SpanRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发请求之间不得串链：每个线程的 traceId 独立，span 归属正确。
 */
class TraceContextConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TraceContextConcurrencyTest.class);
    private static final int THREADS = 32;
    private static final int SPANS_PER_THREAD = 20;

    private TraceStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore(THREADS * 2);
        TraceContext.configure(store, 500);
    }

    @AfterEach
    void tearDown() {
        TraceContext.endTrace();
    }

    @Test
    void concurrentTracesDoNotInterfere() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Map<String, String> threadToTrace = new ConcurrentHashMap<>();
        List<Throwable> failures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            final String worker = "worker-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    String traceId = TraceContext.startTrace();
                    threadToTrace.put(worker, traceId);
                    for (int s = 0; s < SPANS_PER_THREAD; s++) {
                        TraceContext.startSpan(worker + "-op-" + s);
                        TraceContext.endSpan();
                    }
                    // 判定依据：整个生命周期内当前线程看到的 traceId 必须恒定
                    assertEquals(traceId, TraceContext.currentTraceId(),
                            "traceId changed mid-request for " + worker);
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                } finally {
                    TraceContext.endTrace();
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertTrue(failures.isEmpty(), "worker failures: " + failures);

        // 判定依据：每条追踪恰好包含本线程的 SPANS_PER_THREAD 个 span，且操作名前缀一致
        for (Map.Entry<String, String> e : threadToTrace.entrySet()) {
            TraceRecord record = store.find(e.getValue()).orElseThrow();
            List<SpanRecord> spans = record.getSpans();
            log.info("traceId={} worker={} spanCount={} 判定依据=span数量=={} 且 operation 均以 '{}' 开头",
                    e.getValue(), e.getKey(), spans.size(), SPANS_PER_THREAD, e.getKey());
            assertEquals(SPANS_PER_THREAD, spans.size(),
                    "span count mismatch for trace " + e.getValue());
            assertTrue(spans.stream().allMatch(s -> s.getOperation().startsWith(e.getKey())),
                    "foreign span leaked into trace " + e.getValue());
        }
        assertEquals(THREADS, threadToTrace.values().stream().distinct().count(),
                "traceIds must be unique across concurrent requests");
    }
}

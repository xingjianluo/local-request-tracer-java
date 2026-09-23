package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.context.ActiveSpan;
import com.github.xingjianluo.localrequesttracerjava.trace.context.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与异常路径下的追踪正确性：
 * 不串链、归属正确、失败类别可区分且不静默丢失。
 */
class TracerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TracerConcurrencyTest.class);

    private final TraceStore store = new BoundedTraceStore(4096);
    private final Tracer tracer = new Tracer(store, new ErrorClassifier(), 500);

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void concurrentTracesNeverCrossWire() throws InterruptedException {
        int threads = 32;
        int spansPerTrace = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<String, String> threadToTrace = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                String thread = Thread.currentThread().getName();
                try {
                    start.await();
                    try (Tracer.TraceHandle handle = tracer.beginTrace("root", "TEST")) {
                        threadToTrace.put(thread, handle.traceId());
                        for (int s = 0; s < spansPerTrace; s++) {
                            tracer.run("child-" + s, "INTERNAL", () -> {
                            });
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发 trace 应在限时内完成");
        pool.shutdownNow();
        assertEquals(0, failures.get(), "并发执行不应出错");

        // 每条 trace：1 根 + 5 子，且所有 span 的 traceId 一致、父链完整
        for (String traceId : threadToTrace.values()) {
            Optional<TraceSnapshot> found = tracer.find(traceId);
            assertTrue(found.isPresent(), "trace 必须落库: " + traceId);
            TraceSnapshot snapshot = found.get();
            log.info("trace check traceId={} spans={} status={} basis=每线程独立trace应含1根+{}子",
                    traceId, snapshot.spans().size(), snapshot.status(), spansPerTrace);
            assertEquals(1 + spansPerTrace, snapshot.spans().size(), "span 数量必须完整，不得漏记");
            String rootSpanId = null;
            for (SpanData span : snapshot.spans()) {
                if (span.parentSpanId() == null) {
                    rootSpanId = span.spanId();
                }
            }
            assertNotNull(rootSpanId, "必须存在根 span");
            for (SpanData span : snapshot.spans()) {
                if (span.parentSpanId() != null) {
                    assertEquals(rootSpanId, span.parentSpanId(),
                            "子 span 必须挂在本 trace 的根下，不得跨 trace 归属");
                }
            }
        }
        // 并发后线程上下文必须清理干净
        assertNull(TraceContext.currentTraceId(), "trace 结束后上下文必须清理，防止串链");
    }

    @Test
    void asyncChildSpansAttachToSameTrace() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        String traceId;
        try (Tracer.TraceHandle handle = tracer.beginTrace("root", "TEST")) {
            traceId = handle.traceId();
            ActiveSpan root = TraceContext.currentSpan();
            // 模拟异步提交：capture → 另一线程 attach → 开子 span
            TraceContext.ContextSnapshot snapshot = TraceContext.capture();
            CountDownLatch done = new CountDownLatch(1);
            pool.submit(() -> {
                try (TraceContext.Scope ignored = TraceContext.attach(snapshot)) {
                    tracer.run("async-child", "INTERNAL", () -> {
                    });
                } finally {
                    done.countDown();
                }
            });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNotNull(root);
        }
        pool.shutdownNow();

        TraceSnapshot result = tracer.find(traceId).orElseThrow();
        SpanData asyncSpan = result.spans().stream()
                .filter(s -> "async-child".equals(s.name()))
                .findFirst().orElseThrow();
        SpanData rootSpan = result.spans().stream()
                .filter(s -> s.parentSpanId() == null)
                .findFirst().orElseThrow();
        log.info("async attach traceId={} asyncSpan.parent={} root={} basis=异步子span父节点必须等于提交线程根span",
                traceId, asyncSpan.parentSpanId(), rootSpan.spanId());
        assertEquals(rootSpan.spanId(), asyncSpan.parentSpanId(),
                "异步子操作必须归属同一 trace 的提交点 span");
    }

    @Test
    void failuresAreClassifiedAndNeverSilentlyDropped() {
        List<String> traceIds = new ArrayList<>();
        // 三种不同失败 + 一种成功，各自独立 trace
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            traceIds.add(h.traceId());
            tracer.run("validation-op", "INTERNAL", () -> {
                throw new IllegalArgumentException("bad");
            });
        } catch (RuntimeException ignored) {
        }
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            traceIds.add(h.traceId());
            tracer.run("timeout-op", "INTERNAL", () -> {
                tracer.markCurrentFailed(new java.util.concurrent.TimeoutException("slow"));
            });
        }
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            traceIds.add(h.traceId());
            tracer.run("ok-op", "INTERNAL", () -> {
            });
        }

        TraceSnapshot failed1 = tracer.find(traceIds.get(0)).orElseThrow();
        TraceSnapshot failed2 = tracer.find(traceIds.get(1)).orElseThrow();
        TraceSnapshot ok = tracer.find(traceIds.get(2)).orElseThrow();

        log.info("failure attribution traceId={} status={} category={} basis=参数异常→VALIDATION_ERROR",
                traceIds.get(0), failed1.status(), categoryOf(failed1, "validation-op"));
        log.info("failure attribution traceId={} status={} category={} basis=超时→TIMEOUT",
                traceIds.get(1), failed2.status(), categoryOf(failed2, "timeout-op"));
        log.info("success attribution traceId={} status={} basis=无异常→SUCCESS",
                traceIds.get(2), ok.status());

        assertEquals(SpanStatus.FAILED, failed1.status(), "失败必须反映到 trace 级状态，不得静默丢失");
        assertEquals(ErrorCategory.VALIDATION_ERROR, categoryOf(failed1, "validation-op"));
        assertEquals(ErrorCategory.TIMEOUT, categoryOf(failed2, "timeout-op"));
        assertEquals(SpanStatus.SUCCESS, ok.status(), "成功 trace 不得被其他 trace 的失败污染");
    }

    private ErrorCategory categoryOf(TraceSnapshot snapshot, String spanName) {
        return snapshot.spans().stream()
                .filter(s -> spanName.equals(s.name()))
                .findFirst()
                .map(SpanData::errorCategory)
                .orElse(null);
    }
}

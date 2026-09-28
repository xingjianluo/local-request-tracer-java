package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.context.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SlowestSpan;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 一层归因汇总：整链最终失败类别、最慢一环（名字+耗时），
 * 以及同一失败走同步/异步/包装不同路径时整链归类必须一致。
 */
class TraceAttributionTest {

    private static final Logger log = LoggerFactory.getLogger(TraceAttributionTest.class);

    private final TraceStore store = new BoundedTraceStore(4096);
    private final Tracer tracer = new Tracer(store, new ErrorClassifier(), 500);

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void successTraceCarriesSlowestSpanAndNoErrorCategory() {
        String id;
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            id = h.traceId();
            tracer.run("quick", "INTERNAL", () -> {
            });
            tracer.run("slow-ish", "INTERNAL", () -> sleep(30));
        }
        TraceSnapshot snapshot = tracer.find(id).orElseThrow();
        assertEquals(SpanStatus.SUCCESS, snapshot.status());
        assertNull(snapshot.attribution().errorCategory(), "成功链路不得有失败类别");
        SlowestSpan slowest = snapshot.attribution().slowestSpan();
        assertNotNull(slowest);
        assertEquals("slow-ish", slowest.name(),
                "最慢一环只在子 span 中选，根 span 包住整链不参与");
        assertTrueAtLeast(slowest.durationMs(), 20);
        log.info("success attribution id={} slowest={}/{}ms", id, slowest.name(), slowest.durationMs());
    }

    @Test
    void failedTraceAttributesToFirstFailedCategoryAndSlowestSpan() {
        String id = null;
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            id = h.traceId();
            tracer.run("fast-fail", "INTERNAL", () -> {
                throw new IllegalArgumentException("bad");
            });
        } catch (RuntimeException ignored) {
        }
        TraceSnapshot snapshot = tracer.find(id).orElseThrow();
        assertEquals(SpanStatus.FAILED, snapshot.status());
        assertEquals(ErrorCategory.VALIDATION_ERROR, snapshot.attribution().errorCategory(),
                "整链最终类别取第一个失败 span 的归一化类别");
        assertEquals("fast-fail", snapshot.attribution().slowestSpan().name(),
                "失败 span 也参与最慢一环比较");
        log.info("failed attribution id={} category={} slowest={}",
                id, snapshot.attribution().errorCategory(), snapshot.attribution().slowestSpan().name());
    }

    @Test
    void sameRootCauseHasSameTraceCategoryAcrossSyncAsyncAndWrappers() throws InterruptedException {
        // 路径 1：同步直接抛 ConnectException
        final String[] syncIdHolder = new String[1];
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            syncIdHolder[0] = h.traceId();
            tracer.call("downstream", "HTTP", () -> {
                throw new RuntimeException(new ConnectException("refused"));
            });
        } catch (RuntimeException ignored) {
        }
        String syncId = syncIdHolder[0];

        // 路径 2：异步线程执行、上下文跨线程传播，根因相同
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String asyncId;
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            asyncId = h.traceId();
            TraceContext.ContextSnapshot captured = TraceContext.capture();
            CountDownLatch done = new CountDownLatch(1);
            pool.submit(() -> {
                try (TraceContext.Scope ignored = TraceContext.attach(captured)) {
                    tracer.call("downstream", "HTTP", () -> {
                        throw new CompletionException(new CompletionException(new ConnectException("refused")));
                    });
                } catch (CompletionException expected) {
                    // 包装后的根因被剥到 ConnectException
                } finally {
                    done.countDown();
                }
            });
            done.await(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 路径 3：CompletableFuture 链路中转译一次
        String futureId;
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            futureId = h.traceId();
            CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
                throw new CompletionException(new ConnectException("refused"));
            });
            try {
                tracer.run("wait-future", "HTTP", () -> cf.join());
            } catch (CompletionException ignored) {
            }
        }

        TraceSnapshot sync = tracer.find(syncId).orElseThrow();
        TraceSnapshot async = tracer.find(asyncId).orElseThrow();
        TraceSnapshot future = tracer.find(futureId).orElseThrow();

        for (TraceSnapshot s : java.util.List.of(sync, async, future)) {
            assertEquals(SpanStatus.FAILED, s.status());
            assertEquals(ErrorCategory.UPSTREAM_FAILURE, s.attribution().errorCategory(),
                    "同步/异步/包装路径不同，整链归类必须一致");
        }
        log.info("consistent attribution sync={} async={} future={}",
                sync.attribution().errorCategory(),
                async.attribution().errorCategory(),
                future.attribution().errorCategory());
    }

    @Test
    void rootOnlyTraceFallsBackSlowestToRootAndTieGoesToEarlier() {
        // 只有根 span 的失败链路：slowestSpan 回退到根，不返回 null
        TraceSnapshot rootOnly = new TraceSnapshot(
                "t-root-only", 100, 140, 40, SpanStatus.FAILED,
                java.util.List.of(new com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData(
                        "root-id", null, "GET /x", "SERVER", 100, 40, "main",
                        SpanStatus.FAILED, ErrorCategory.TIMEOUT, null, null)));
        assertNotNull(rootOnly.attribution().slowestSpan());
        assertEquals("GET /x", rootOnly.attribution().slowestSpan().name());
        assertEquals(ErrorCategory.TIMEOUT, rootOnly.attribution().errorCategory());

        // 两个等耗时子 span：取列表中更早的，结论确定
        TraceSnapshot tied = new TraceSnapshot("t-tie", 0, 10, 10, SpanStatus.SUCCESS,
                java.util.List.of(
                        new com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData(
                                "r", null, "root", "SERVER", 0, 10, "t",
                                SpanStatus.SUCCESS, null, null, null),
                        span("c1", "first", 5, SpanStatus.SUCCESS, null),
                        span("c2", "second", 5, SpanStatus.SUCCESS, null)));
        assertEquals("first", tied.attribution().slowestSpan().name(), "并列耗时取更早一环");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertTrueAtLeast(long actual, long expected) {
        org.junit.jupiter.api.Assertions.assertTrue(actual >= expected,
                "actual=" + actual + " expected>=" + expected);
    }

    private static com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData span(
            String id, String name, long durationMs, SpanStatus status, ErrorCategory category) {
        return new com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData(
                id, "r", name, "INTERNAL", 0, durationMs, "t", status, category, null, null);
    }
}

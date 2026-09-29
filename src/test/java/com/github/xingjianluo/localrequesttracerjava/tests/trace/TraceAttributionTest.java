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
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void specificMiddleLayerStillDefersToDeeperRootCauseAcrossPaths() throws InterruptedException {
        // 关键场景：中间那层异常本身也对得上具体类别（IllegalStateException→CONFLICT），
        // 但更深处是明确的超时根因。同一条根因走 同步直接抛 / 异步包装 / 中间转译，
        // 整链最终类别必须都是 TIMEOUT——这样按 TIMEOUT 筛选时一次查全，不被路径拆分。
        final String[] directIdHolder = new String[1];
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            directIdHolder[0] = h.traceId();
            tracer.call("downstream", "HTTP", () -> {
                throw new java.util.concurrent.CompletionException(new TimeoutException("slow"));
            });
        } catch (RuntimeException ignored) {
        }
        String directId = directIdHolder[0];

        final String[] translatedIdHolder = new String[1];
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            translatedIdHolder[0] = h.traceId();
            tracer.call("downstream", "HTTP", () -> {
                // 中间层把超时转译成状态冲突：外层 CONFLICT 具体、深处 TIMEOUT 更具体
                throw new IllegalStateException(new TimeoutException("slow"));
            });
        } catch (RuntimeException ignored) {
        }
        String translatedId = translatedIdHolder[0];

        final String[] asyncIdHolder = new String[1];
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(1);
        try (Tracer.TraceHandle h = tracer.beginTrace("root", "TEST")) {
            asyncIdHolder[0] = h.traceId();
            TraceContext.ContextSnapshot captured = TraceContext.capture();
            pool.submit(() -> {
                try (TraceContext.Scope ignored = TraceContext.attach(captured)) {
                    tracer.call("downstream", "HTTP", () -> {
                        throw new CompletionException(new IllegalStateException(new TimeoutException("slow")));
                    });
                } catch (CompletionException expected) {
                    // 异步包装 + 具体中间层 + 深处超时，根因不变
                } finally {
                    done.countDown();
                }
            });
            done.await(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        String asyncId = asyncIdHolder[0];

        for (String id : java.util.List.of(directId, translatedId, asyncId)) {
            TraceSnapshot s = tracer.find(id).orElseThrow();
            assertEquals(SpanStatus.FAILED, s.status(), id);
            assertEquals(ErrorCategory.TIMEOUT, s.attribution().errorCategory(),
                    "外层/中间层对得上 CONFLICT 时，仍以深处超时根因 TIMEOUT 为准：" + id);
        }

        // 按失败类别筛选必须一次查全三条路径的同根因记录，不拆成 TIMEOUT/CONFLICT 两类
        TracePage page = tracer.query(new TraceQuery(0, 0, null, ErrorCategory.TIMEOUT, 100, null));
        List<String> timeoutIds = page.items().stream().map(TraceSummary::traceId).toList();
        assertTrue(timeoutIds.contains(directId), "异步包装路径必须能被 TIMEOUT 条件查全");
        assertTrue(timeoutIds.contains(translatedId), "中间层转译路径必须能被 TIMEOUT 条件查全");
        assertTrue(timeoutIds.contains(asyncId), "异步+转译路径必须能被 TIMEOUT 条件查全");
        for (TraceSummary item : page.items()) {
            assertEquals(ErrorCategory.TIMEOUT, item.attribution().errorCategory());
        }
        // 同批记录不得被误归到 CONFLICT
        TracePage conflicts = tracer.query(new TraceQuery(0, 0, null, ErrorCategory.CONFLICT, 100, null));
        for (TraceSummary item : conflicts.items()) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    java.util.List.of(directId, translatedId, asyncId).contains(item.traceId()),
                    "同根因记录不得被路径拆到 CONFLICT 类");
        }
        log.info("deeper-root attribution direct={} translated={} async={}",
                directId, translatedId, asyncId);
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

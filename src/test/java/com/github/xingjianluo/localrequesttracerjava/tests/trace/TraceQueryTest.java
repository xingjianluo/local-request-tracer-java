package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceSummary;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量查询：多条件筛选、按结束时间倒序（traceId 稳定次序）、限量与游标分页，
 * 以及后台持续写入时同条件结果不抖动、不出现半截条目。
 */
class TraceQueryTest {

    private static final Logger log = LoggerFactory.getLogger(TraceQueryTest.class);

    private static SpanData span(String id, String name, long durationMs,
                                 SpanStatus status, ErrorCategory category) {
        return new SpanData(id, null, name, "INTERNAL", 1L, durationMs,
                "t", status, category, null, null);
    }

    private static TraceSnapshot failed(String traceId, long endMs, ErrorCategory category,
                                        long slowDuration, String slowName) {
        return new TraceSnapshot(traceId, endMs - 100, endMs, 100, SpanStatus.FAILED,
                List.of(span(traceId + "-a", "first-op", 10, SpanStatus.FAILED, category),
                        span(traceId + "-b", slowName, slowDuration, SpanStatus.SUCCESS, null)));
    }

    private static TraceSnapshot success(String traceId, long endMs) {
        return new TraceSnapshot(traceId, endMs - 50, endMs, 50, SpanStatus.SUCCESS,
                List.of(span(traceId + "-a", "ok-op", 5, SpanStatus.SUCCESS, null)));
    }

    private static TraceQuery query(long from, long to, SpanStatus status,
                                    ErrorCategory category, int limit, String cursor) {
        return new TraceQuery(from, to, status, category, limit, cursor);
    }

    private static List<String> idsOf(TracePage page) {
        return page.items().stream().map(TraceSummary::traceId).toList();
    }

    @Test
    void filtersByTimeWindowStatusAndCategoryWithStableOrdering() {
        BoundedTraceStore store = new BoundedTraceStore(100);
        store.save(failed("f-timeout-1", 1_000, ErrorCategory.TIMEOUT, 300, "wait-db"));
        store.save(failed("f-timeout-2", 2_000, ErrorCategory.TIMEOUT, 800, "wait-http"));
        store.save(failed("f-valid-1", 1_500, ErrorCategory.VALIDATION_ERROR, 100, "validate"));
        store.save(success("s-ok-1", 1_200));
        store.save(success("s-ok-2", 2_100));

        // 只看失败
        TracePage failedOnly = store.query(query(0, 0, SpanStatus.FAILED, null, 50, null));
        assertEquals(List.of("f-timeout-2", "f-valid-1", "f-timeout-1"),
                idsOf(failedOnly), "失败结果按结束时间倒序");

        // 只看成功
        TracePage okOnly = store.query(query(0, 0, SpanStatus.SUCCESS, null, 50, null));
        assertEquals(List.of("s-ok-2", "s-ok-1"), idsOf(okOnly));

        // 按失败类别筛
        TracePage timeouts = store.query(query(0, 0, null, ErrorCategory.TIMEOUT, 50, null));
        assertEquals(List.of("f-timeout-2", "f-timeout-1"), idsOf(timeouts),
                "失败类别筛选隐含只取失败，且同类别路径不同也不会被拆开");
        for (TraceSummary s : timeouts.items()) {
            assertEquals(ErrorCategory.TIMEOUT, s.attribution().errorCategory());
            assertEquals(SpanStatus.FAILED, s.status());
        }

        // 时间窗（含边界）
        TracePage window = store.query(query(1_200, 2_000, null, null, 50, null));
        assertEquals(List.of("f-timeout-2", "f-valid-1", "s-ok-1"), idsOf(window),
                "时间窗两端含边界");

        log.info("filtered failed={} timeouts={} window={}",
                idsOf(failedOnly), idsOf(timeouts), idsOf(window));
    }

    @Test
    void tieOnSameEndTimeBreaksByTraceIdDescAndPaginationIsStable() {
        BoundedTraceStore store = new BoundedTraceStore(100);
        // 同一结束时间的 5 条，验证 traceId 次序稳定可复现
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = "tie-%d".formatted(i);
            ids.add(id);
            store.save(success(id, 1_000));
        }
        List<String> expected = ids.stream().sorted(java.util.Comparator.reverseOrder()).toList();

        TracePage p1 = store.query(query(0, 0, null, null, 2, null));
        assertEquals(expected.subList(0, 2), idsOf(p1));
        assertTrue(p1.hasMore());
        assertNotNull(p1.nextCursor());

        TracePage p2 = store.query(query(0, 0, null, null, 2, p1.nextCursor()));
        assertEquals(expected.subList(2, 4), idsOf(p2), "翻页不重不漏");

        TracePage p3 = store.query(query(0, 0, null, null, 2, p2.nextCursor()));
        assertEquals(expected.subList(4, 5), idsOf(p3));
        assertFalse(p3.hasMore());
        assertNull(p3.nextCursor(), "最后一页不再给游标");

        // 游标自洽：从任意一页游标继续，拼起来等于全量
        TracePage all = store.query(query(0, 0, null, null, 50, null));
        List<String> stitched = new ArrayList<>();
        stitched.addAll(idsOf(p1));
        stitched.addAll(idsOf(p2));
        stitched.addAll(idsOf(p3));
        assertEquals(idsOf(all), stitched, "游标分页拼接必须等于一次性全量，次序可复现");
    }

    @Test
    void limitDefaultsAndIsCappedAndBadCursorRestartsFromHead() {
        BoundedTraceStore store = new BoundedTraceStore(500);
        for (int i = 0; i < 300; i++) {
            store.save(success("r-%03d".formatted(i), 1_000 + i));
        }
        TracePage capped = store.query(query(0, 0, null, null, 10_000, null));
        assertEquals(TraceQuery.MAX_LIMIT, capped.items().size(), "limit 必须有上限");

        TracePage defaulted = store.query(query(0, 0, null, null, 0, null));
        assertEquals(TraceQuery.DEFAULT_LIMIT, defaulted.items().size(), "limit<=0 用默认值");

        TracePage badCursor = store.query(query(0, 0, null, null, 500, "@@@not-base64@@@"));
        TracePage noCursor = store.query(query(0, 0, null, null, 500, null));
        assertEquals(idsOf(noCursor), idsOf(badCursor), "损坏游标按从头开始处理，不报错");
    }

    @Test
    void repeatedQueriesStayStableWhileWritesContinueAndItemsAreWhole() throws InterruptedException {
        // 容量需容纳"基准数据 + 整个并发写入期间的全部新数据"：
        // 否则持续写入触发 FIFO 顶掉窗口内基准数据，属于容量语义的正常行为，
        // 会让"窗口内集合稳定"这个前提不成立。
        BoundedTraceStore store = new BoundedTraceStore(200_512);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch baseReady = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicReference<Throwable> writerError = new AtomicReference<>();

        pool.submit(() -> {
            try {
                baseReady.await();
                long end = 10_000;
                int i = 0;
                // 上限 100_000，配合 200_512 的容量，任何时刻都不会发生容量回收；
                // stop 信号到达后通常还会先写完一批，但总量仍远低于容量。
                while (stop.getCount() > 0 && i < 100_000) {
                    // 全部落在固定时间窗之后，保证查询窗口内集合稳定
                    store.save(success("w-%05d".formatted(i++), end++));
                }
            } catch (Throwable t) {
                writerError.set(t);
            }
        });

        // 固定查一个老时间窗：写入线程的数据都在窗口外。
        // 先把窗口内 30 条基准数据全部落库，再放行持续写入，然后重复查询验证不抖动。
        for (int i = 0; i < 30; i++) {
            store.save(failed("base-%02d".formatted(i), 1_000 + i,
                    ErrorCategory.TIMEOUT, 10, "x"));
        }
        baseReady.countDown();
        List<String> first = null;
        for (int round = 0; round < 50; round++) {
            TracePage page = store.query(query(0, 5_000, SpanStatus.FAILED,
                    ErrorCategory.TIMEOUT, 10, null));
            List<String> current = idsOf(page);
            assertEquals(10, current.size(), "窗口内条目数必须稳定");
            for (TraceSummary s : page.items()) {
                assertNotNull(s.attribution(), "列表条目必须带完整归因，不得是半截");
                assertEquals(ErrorCategory.TIMEOUT, s.attribution().errorCategory());
                assertTrue(s.endEpochMs() <= 5_000, "窗口外的新写入不得混入");
            }
            if (first == null) {
                first = current;
            } else {
                assertEquals(first, current, "后台持续写入不得让同一批结果抖动");
            }
        }
        stop.countDown();
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        assertNull(writerError.get(), "写入线程不应出错");
        log.info("stable query under writes, firstPage={}", first);
    }
}

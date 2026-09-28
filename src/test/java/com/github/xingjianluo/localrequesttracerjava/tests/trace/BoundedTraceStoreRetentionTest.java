package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.LatencyClass;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSummary;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 留存与查询：TTL 过期回收、容量淘汰、墓碑区分、两者同时触发的边界、
 * 多条件筛选、稳定排序与游标分页。时钟可注入，行为确定性可复现。
 */
class BoundedTraceStoreRetentionTest {

    /** 可控时钟。 */
    private static final class MutableClock {
        final AtomicLong now = new AtomicLong(1_000_000L);

        long get() {
            return now.get();
        }

        void advance(long delta) {
            now.addAndGet(delta);
        }
    }

    private static SpanData span(String id, long start, long duration,
                                 SpanStatus status, ErrorCategory category) {
        return new SpanData(id, null, "span-" + id, "INTERNAL", start, duration,
                "t", status, category,
                category == null ? null : category.safeMessage(), LatencyClass.NORMAL);
    }

    private static TraceSnapshot trace(String id, long start, long end, SpanStatus status,
                                       List<SpanData> spans) {
        return new TraceSnapshot(id, start, end, end - start, status, spans);
    }

    private static TraceSnapshot ok(String id, long start) {
        return trace(id, start, start + 10, SpanStatus.SUCCESS,
                List.of(span(id + "-s", start, 10, SpanStatus.SUCCESS, null)));
    }

    private static TraceSnapshot failed(String id, long start, ErrorCategory category) {
        return trace(id, start, start + 10, SpanStatus.FAILED,
                List.of(span(id + "-s", start, 10, SpanStatus.FAILED, category)));
    }

    private BoundedTraceStore store(int capacity, long ttl, MutableClock clock) {
        return new BoundedTraceStore(capacity, ttl, clock::get);
    }

    @Test
    void ttlExpiresOldTracesAndDistinguishesFromUnknown() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(10, 100L, clock);
        s.save(ok("old", clock.get()));
        clock.advance(50);
        s.save(ok("young", clock.get()));

        // 未到 TTL：都在
        assertEquals(FindStatus.FOUND, s.findDetailed("old").status());
        clock.advance(60); // old 已存活 110ms，young 60ms

        assertEquals(FindStatus.EXPIRED, s.findDetailed("old").status(),
                "超过 TTL 必须能识别为已过期回收");
        assertEquals(FindStatus.FOUND, s.findDetailed("young").status(), "未到 TTL 的保留");
        assertEquals(FindStatus.UNKNOWN, s.findDetailed("never").status(),
                "从未记录与已过期必须可区分");
    }

    @Test
    void capacityEvictionMarksEvictedAndExpiredWinsWhenBothApply() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(2, 100L, clock);
        s.save(ok("a", clock.get()));
        clock.advance(10);
        s.save(ok("b", clock.get()));
        clock.advance(10);
        s.save(ok("c", clock.get())); // 容量淘汰 a

        assertEquals(FindStatus.EVICTED, s.findDetailed("a").status(),
                "容量上限淘汰应标记为 EVICTED");
        assertEquals(2, s.size());

        clock.advance(100); // b 也已过期
        s.save(ok("d", clock.get())); // 触发 TTL + 容量，规则先到先算：TTL 先清 b
        assertEquals(FindStatus.EXPIRED, s.findDetailed("b").status(),
                "同时满足 TTL 与容量时，过期口径仍应可识别为 EXPIRED");
        assertEquals(FindStatus.FOUND, s.findDetailed("d").status());
        assertTrue(s.size() <= 2, "两条规则同时触发，size 仍不得超过容量");
    }

    @Test
    void ttlDisabledKeepsCapacityOnlyBehavior() {
        BoundedTraceStore s = new BoundedTraceStore(2);
        s.save(ok("a", 1L));
        s.save(ok("b", 2L));
        s.save(ok("c", 3L));
        assertEquals(FindStatus.EVICTED, s.findDetailed("a").status());
        assertEquals(0, s.purgeExpired(Long.MAX_VALUE), "未启用 TTL 时清理为空操作");
    }

    @Test
    void resavingAfterExpiryMakesItFoundAgain() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(10, 100L, clock);
        s.save(ok("t", clock.get()));
        clock.advance(200);
        assertEquals(FindStatus.EXPIRED, s.findDetailed("t").status());
        s.save(ok("t", clock.get()));
        assertEquals(FindStatus.FOUND, s.findDetailed("t").status(), "重新记录后墓碑应作废");
    }

    @Test
    void queryFiltersByTimeRangeStatusAndCategoryWithStableOrder() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(100, 0L, clock);
        s.save(ok("ok-1", 100));
        s.save(failed("fail-timeout", 200, ErrorCategory.TIMEOUT));
        s.save(ok("ok-2", 300));
        s.save(failed("fail-validation", 400, ErrorCategory.VALIDATION_ERROR));

        // 时间范围（闭区间，按开始时间），结果倒序
        TracePage range = s.query(new TraceQuery(150L, 350L, null, null, null, null));
        assertEquals(List.of("ok-2", "fail-timeout"), ids(range), "时间范围应按开始时间过滤并倒序");

        // 只看失败
        TracePage failedOnly = s.query(new TraceQuery(null, null, SpanStatus.FAILED, null, null, null));
        assertEquals(List.of("fail-validation", "fail-timeout"), ids(failedOnly),
                "必须按开始时间倒序");

        // 只看失败 + 类别 TIMEOUT
        TracePage timeouts = s.query(new TraceQuery(null, null, SpanStatus.FAILED,
                ErrorCategory.TIMEOUT, null, null));
        assertEquals(List.of("fail-timeout"), ids(timeouts));

        // 成功结果不会匹配失败类别
        TracePage none = s.query(new TraceQuery(null, null, SpanStatus.SUCCESS,
                ErrorCategory.TIMEOUT, null, null));
        assertTrue(none.items().isEmpty());

        // 全量倒序
        TracePage all = s.query(new TraceQuery(null, null, null, null, null, null));
        assertEquals(List.of("fail-validation", "ok-2", "fail-timeout", "ok-1"), ids(all));
    }

    @Test
    void queryOrderIsStableForSameTimestampByTraceId() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(100, 0L, clock);
        s.save(ok("id-a", 100));
        s.save(ok("id-c", 100));
        s.save(ok("id-b", 100));

        TracePage page = s.query(new TraceQuery(null, null, null, null, null, null));
        assertEquals(List.of("id-c", "id-b", "id-a"), ids(page),
                "相同开始时间必须按 traceId 倒序兜底，排序稳定可复现");
    }

    @Test
    void cursorPagingVisitsEveryRecordExactlyOnce() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(100, 0L, clock);
        for (int i = 0; i < 10; i++) {
            s.save(ok("t" + i, 100 + i));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            TracePage p = s.query(new TraceQuery(null, null, null, null, 3, cursor));
            seen.addAll(ids(p));
            cursor = p.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        List<String> expected = new ArrayList<>();
        for (int i = 9; i >= 0; i--) {
            expected.add("t" + i);
        }
        assertEquals(expected, seen, "游标分页必须不重不漏遍历全部记录");
    }

    @Test
    void limitIsBoundedAndInvalidCursorRejected() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(100, 0L, clock);
        for (int i = 0; i < 5; i++) {
            s.save(ok("t" + i, 100 + i));
        }
        assertEquals(5, s.query(new TraceQuery(null, null, null, null, 10_000, null)).items().size()
                , "limit 上限 500 由存储统一裁剪");

        try {
            s.query(new TraceQuery(null, null, null, null, null, "not-a-cursor"));
            throw new AssertionError("非法游标必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void tombstonesStayBoundedByCapacity() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(4, 0L, clock);
        // 写入远超容量，制造大量淘汰墓碑
        for (int i = 0; i < 100; i++) {
            s.save(ok("t" + i, i));
        }
        // 最早的墓碑应被有界回收：t0 已无法区分（墓碑老化），这是有界的明确代价
        // 最近被淘汰的仍可识别
        assertEquals(FindStatus.EVICTED, s.findDetailed("t95").status());
        assertEquals(4, s.size());
    }

    @Test
    void expiredTombstonesAgeOutAfterOneTtl() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(10, 100L, clock);
        long start = clock.get();
        s.save(ok("t", start)); // 结束时间为 start+10，TTL 自结束起算
        clock.advance(111);     // 距结束已 101ms，超过 TTL
        assertEquals(FindStatus.EXPIRED, s.findDetailed("t").status());
        // 再经过一个 TTL 周期并触发清扫，墓碑老化 → 变成 UNKNOWN（有界）
        clock.advance(100);
        s.purgeExpired(clock.get());
        assertEquals(FindStatus.UNKNOWN, s.findDetailed("t").status(),
                "墓碑留满一个 TTL 后必须老化，保证内存有界");
    }

    @Test
    void queryResultsAreImmutableSnapshots() {
        MutableClock clock = new MutableClock();
        BoundedTraceStore s = store(100, 0L, clock);
        s.save(ok("t", 100));
        TracePage page = s.query(new TraceQuery(null, null, null, null, null, null));
        List<TraceSummary> items = page.items();
        try {
            items.add(items.get(0));
            throw new AssertionError("列表结果必须不可变");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        assertNotNull(page.items().get(0).attribution());
        assertFalse(page.items().get(0).trace().spans().isEmpty());
    }

    private static List<String> ids(TracePage page) {
        return page.items().stream().map(ts -> ts.trace().traceId()).toList();
    }
}

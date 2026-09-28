package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.LookupStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceLookup;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.tests.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期回收与容量上限：规则边界、同时触发、"过期/顶掉/从未有过"三态可区分、
 * 以及清理过程中并发读取的完整性。
 */
class TraceRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionTest.class);

    private static TraceSnapshot snapshot(String id, long endMs) {
        return new TraceSnapshot(id, endMs - 10, endMs, 10, SpanStatus.SUCCESS, List.of());
    }

    @Test
    void expiryBoundaryIsEndPlusTtlAndLookupHasThreeStates() {
        MutableClock clock = new MutableClock(1_000);
        BoundedTraceStore store = new BoundedTraceStore(10, 100, clock);
        // end=1000；ttl=100：now=1099 未过期，now=1100 恰好过期（先到先算）
        store.save(snapshot("t-boundary", 1_000));

        clock.setMillis(1_099);
        assertEquals(LookupStatus.FOUND, store.lookup("t-boundary").status(),
                "end + ttl - 1 仍在保留期内");

        clock.setMillis(1_100);
        TraceLookup expired = store.lookup("t-boundary");
        assertEquals(LookupStatus.EXPIRED, expired.status(),
                "end + ttl 恰好到期，应判定为已过期回收");
        assertFalse(expired.isFound(), "过期结论不得再带快照");

        TraceLookup never = store.lookup("never-existed");
        assertEquals(LookupStatus.NEVER_RECORDED, never.status(),
                "从未记录的标识必须与过期区分开");
        assertFalse(store.find("t-boundary").isPresent());

        log.info("expiry states expired={} never={} basis=end+ttl先到先算，三态可区分",
                expired.status(), never.status());
    }

    @Test
    void capacityEvictionReportsEvictedStateAndFifo() {
        MutableClock clock = new MutableClock(1_000);
        BoundedTraceStore store = new BoundedTraceStore(2, 0, clock);
        store.save(snapshot("c1", 1_000));
        store.save(snapshot("c2", 1_001));
        store.save(snapshot("c3", 1_002));

        assertEquals(LookupStatus.EVICTED, store.lookup("c1").status(),
                "容量顶掉必须登记为 EVICTED，与过期区分");
        assertEquals(LookupStatus.FOUND, store.lookup("c2").status());
        assertEquals(LookupStatus.FOUND, store.lookup("c3").status());
        assertEquals(LookupStatus.NEVER_RECORDED, store.lookup("nope").status());
    }

    @Test
    void expiryRunsBeforeCapacityWhenBothTrigger() {
        // 容量 2，ttl 100：落第 3 条时，old 先被过期回收；
        // 若没有过期这一步，容量 FIFO 本应顶掉 old——验证离开原因仍按"先到先算"记 EXPIRED。
        MutableClock clock = new MutableClock(900);
        BoundedTraceStore store = new BoundedTraceStore(2, 100, clock);
        store.save(snapshot("old", 900));    // now=900 落库，now=1000 恰好过期
        clock.setMillis(950);
        store.save(snapshot("mid", 950));    // now=1050 才过期
        clock.setMillis(1_000);
        store.save(snapshot("new", 1_000));  // 保存时：先过期 old，再容量检查（mid/new 恰好 2 条）

        assertEquals(2, store.size(), "过期先回收，容量保留 mid 与 new");
        assertEquals(LookupStatus.EXPIRED, store.lookup("old").status(),
                "因过期离开的必须归 EXPIRED，不能被容量顶掉的口径覆盖（先到先算）");
        assertEquals(LookupStatus.FOUND, store.lookup("mid").status());
        assertEquals(LookupStatus.FOUND, store.lookup("new").status());

        // 再来一条新鲜的：此时容量 FIFO 顶掉 mid，与过期原因区分
        store.save(snapshot("newer", 1_000));
        assertEquals(LookupStatus.EVICTED, store.lookup("mid").status(),
                "容量顶掉的必须归 EVICTED");
        assertEquals(LookupStatus.FOUND, store.lookup("new").status());
        assertEquals(LookupStatus.FOUND, store.lookup("newer").status());

        log.info("both-trigger size={} old={} mid={} basis=先过期、后容量，原因按先到先算",
                store.size(), store.lookup("old").status(), store.lookup("mid").status());
    }

    @Test
    void resaveSameIdClearsRemovalRegistryAndSizeStaysBounded() {
        MutableClock clock = new MutableClock(1_000);
        BoundedTraceStore store = new BoundedTraceStore(1, 100, clock);
        store.save(snapshot("re", 1_000));
        clock.setMillis(1_200);
        store.save(snapshot("other", 1_200)); // re 过期并被登记
        assertEquals(LookupStatus.EXPIRED, store.lookup("re").status());

        // 同一标识重新写入：旧的回收登记应撤销
        store.save(snapshot("re", 1_200));
        // 容量 1：re 重写时 other 被顶掉
        assertEquals(LookupStatus.EVICTED, store.lookup("other").status());
        assertEquals(LookupStatus.FOUND, store.lookup("re").status());
        assertTrue(store.size() <= store.capacity());
    }

    @Test
    void purgeExpiredReturnsCountAndExcludesFromQueries() {
        MutableClock clock = new MutableClock(900);
        BoundedTraceStore store = new BoundedTraceStore(10, 100, clock);
        // 在数据尚未过期的时刻落库
        store.save(snapshot("e1", 900));
        store.save(snapshot("e2", 950));
        store.save(snapshot("keep", 1_090));
        assertEquals(0, store.purgeExpired(), "now=900 时没有记录到期");

        clock.setMillis(1_000);
        assertEquals(1, store.purgeExpired(), "now=1000 时 e1(end=900) 恰好到期，其余未到期");
        assertEquals(2, store.size());

        clock.setMillis(1_100);
        int purged = store.purgeExpired();
        assertEquals(1, purged, "第二次扫描再清掉 e2(end=950)；keep(end=1090) 尚未到期");
        assertEquals(1, store.size());
        assertEquals(LookupStatus.FOUND, store.lookup("keep").status());
        assertEquals(LookupStatus.EXPIRED, store.lookup("e1").status());
        assertEquals(LookupStatus.EXPIRED, store.lookup("e2").status());
    }

    @Test
    void concurrentSweepsAndReadsNeverYieldPartialRecords() throws InterruptedException {
        int capacity = 128;
        MutableClock clock = new MutableClock(0);
        BoundedTraceStore store = new BoundedTraceStore(capacity, 500, clock);

        // 预置：一半已经过期、一半新鲜
        for (int i = 0; i < capacity; i++) {
            long end = i < capacity / 2 ? 100 : 700;
            store.save(snapshot("init-" + i, end));
        }

        int readers = 8;
        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers + writers);
        AtomicInteger violations = new AtomicInteger();

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        String id = "init-" + (i % capacity);
                        TraceLookup lookup = store.lookup(id);
                        if (lookup.isFound()) {
                            TraceSnapshot s = lookup.snapshot();
                            // 命中就必须是字段完整、自洽的不可变快照
                            if (!id.equals(s.traceId()) || s.endEpochMs() < s.startEpochMs()) {
                                violations.incrementAndGet();
                            }
                            if (s.attribution() == null) {
                                violations.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int w = 0; w < writers; w++) {
            int id = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1_000; i++) {
                        clock.advance(1);
                        store.save(snapshot("w" + id + "-" + i, clock.millis()));
                        store.purgeExpired();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发读写应在限时内结束");
        pool.shutdownNow();

        assertTrue(store.size() <= capacity, "并发清理与写入后不得超过容量");
        assertEquals(0, violations.get(), "并发读取命中时必须是完整自洽记录，不得读到半截");
        assertNotNull(store.lookup("init-0").status());
        log.info("concurrent retention size={} violations={} basis=清理中读取要么完整要么明确没有",
                store.size(), violations.get());
    }
}

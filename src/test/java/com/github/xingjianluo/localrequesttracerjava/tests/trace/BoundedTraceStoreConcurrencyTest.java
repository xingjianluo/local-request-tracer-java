package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量 + TTL 同时工作时的并发正确性：
 * 写入、清理、按标识查询、批量查询并发进行，读不到半截记录、size 不超容量、
 * 列表结果全部自洽完整。
 */
class BoundedTraceStoreConcurrencyTest {

    private static TraceSnapshot snapshot(String id, long start) {
        return new TraceSnapshot(id, start, start + 1, 1L, SpanStatus.SUCCESS, List.of());
    }

    @Test
    void concurrentWritesPurgesAndQueriesStayWhole() throws InterruptedException {
        int capacity = 128;
        long ttlMs = 200L;
        AtomicLong now = new AtomicLong(0L);
        BoundedTraceStore store = new BoundedTraceStore(capacity, ttlMs, now::get);

        int writers = 12;
        int readers = 8;
        int writesEach = 400;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + readers);
        AtomicBoolean failure = new AtomicBoolean();

        for (int w = 0; w < writers; w++) {
            int id = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesEach; i++) {
                        long ts = id * 100_000L + i;
                        store.save(snapshot("w" + id + "-" + i, ts));
                        if ((i & 7) == 0) {
                            now.set(Math.max(now.get(), ts % 500));
                        }
                    }
                } catch (Throwable t) {
                    failure.set(true);
                } finally {
                    done.countDown();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2000 && !Thread.currentThread().isInterrupted(); i++) {
                        // 按标识查询：命中则必须完整自洽，绝不半截
                        String id = "w" + (i % writers) + "-" + (i % writesEach);
                        store.findDetailed(id).optionalDetail().ifPresent(d -> {
                            TraceSnapshot s = d.trace();
                            if (!id.equals(s.traceId()) || s.endEpochMs() < s.startEpochMs()) {
                                failure.set(true);
                            }
                        });
                        // 批量查询：任何一页内记录必须完整，且严格遵守稳定倒序
                        TracePage page = store.query(new TraceQuery(null, null, null,
                                null, 50, null));
                        Long prevTs = null;
                        String prevId = null;
                        for (var item : page.items()) {
                            TraceSnapshot s = item.trace();
                            if (s.spans() == null || s.traceId() == null
                                    || item.attribution() == null) {
                                failure.set(true);
                            }
                            if (prevTs != null) {
                                boolean ordered = s.startEpochMs() < prevTs
                                        || (s.startEpochMs() == prevTs
                                        && s.traceId().compareTo(prevId) < 0);
                                if (!ordered) {
                                    failure.set(true);
                                }
                            }
                            prevTs = s.startEpochMs();
                            prevId = s.traceId();
                        }
                    }
                } catch (Throwable t) {
                    failure.set(true);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务应在限时内完成");
        pool.shutdownNow();

        assertTrue(!failure.get(), "并发写入/清理/查询下不得出现半截记录或乱序结果");
        assertTrue(store.size() <= capacity, "size 不得超过容量");

        // 时钟推到很远，所有记录都应被 TTL 回收
        now.set(Long.MAX_VALUE / 2);
        store.purgeExpired(now.get());
        assertEquals(0, store.size(), "TTL 到达后应全部回收");
    }
}

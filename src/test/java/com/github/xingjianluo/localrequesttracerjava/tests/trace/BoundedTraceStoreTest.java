package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界存储：容量上限确定、FIFO 淘汰确定、高并发写入不出半截记录。
 */
class BoundedTraceStoreTest {

    private static final Logger log = LoggerFactory.getLogger(BoundedTraceStoreTest.class);

    private static TraceSnapshot snapshot(String traceId) {
        return new TraceSnapshot(traceId, 1L, 2L, 1L, SpanStatus.SUCCESS, List.of());
    }

    @Test
    void capacityIsBoundedAndEvictionIsFifo() {
        BoundedTraceStore store = new BoundedTraceStore(3);
        store.save(snapshot("t1"));
        store.save(snapshot("t2"));
        store.save(snapshot("t3"));
        store.save(snapshot("t4"));

        log.info("capacity check size={} capacity={} contains t1={} t4={}",
                store.size(), store.capacity(), store.find("t1").isPresent(), store.find("t4").isPresent());

        assertEquals(3, store.size(), "容量上限必须确定");
        assertFalse(store.find("t1").isPresent(), "最早写入的 t1 应被 FIFO 淘汰");
        assertTrue(store.find("t2").isPresent());
        assertTrue(store.find("t4").isPresent(), "最新写入必须保留");
    }

    @Test
    void concurrentWritesNeverExceedCapacityAndRecordsStayWhole() throws InterruptedException {
        int capacity = 64;
        int writers = 16;
        int perWriter = 200;
        BoundedTraceStore store = new BoundedTraceStore(capacity);

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        for (int w = 0; w < writers; w++) {
            int id = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        store.save(snapshot("trace-" + id + "-" + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入应在限时内完成");
        pool.shutdownNow();

        log.info("concurrent write check size={} capacity={}", store.size(), store.capacity());
        assertEquals(capacity, store.size(), "高并发写入后 size 不得超过容量");

        // 抽验：读到的每条记录字段完整（不可变快照，不存在半截对象）
        for (int i = 0; i < 100; i++) {
            String traceId = "trace-" + (i % writers) + "-" + (perWriter - 1 - i % 10);
            Optional<TraceSnapshot> found = store.find(traceId);
            found.ifPresent(s -> {
                assertEquals(traceId, s.traceId(), "快照 traceId 必须与键一致，不得跨追踪混合");
                assertTrue(s.endEpochMs() >= s.startEpochMs(), "快照时间字段必须完整");
            });
        }
    }
}

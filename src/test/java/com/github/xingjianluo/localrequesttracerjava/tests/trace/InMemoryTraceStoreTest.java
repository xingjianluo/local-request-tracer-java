package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.InMemoryTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.SpanRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储：容量上限确定、淘汰行为确定、并发写入无半截记录、无跨追踪混合。
 */
class InMemoryTraceStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTraceStoreTest.class);

    @Test
    void capacityIsBoundedAndEvictsLeastRecentlyUsed() {
        TraceStore store = new InMemoryTraceStore(3);
        store.getOrCreate("t1");
        store.getOrCreate("t2");
        store.getOrCreate("t3");
        // 访问 t1，使 t2 成为最久未使用
        store.find("t1");
        store.getOrCreate("t4");

        log.info("size={} 判定依据=容量==3 且 t2 被淘汰、t1 保留", store.size());
        assertEquals(3, store.size());
        assertTrue(store.find("t1").isPresent());
        assertFalse(store.find("t2").isPresent());
        assertTrue(store.find("t3").isPresent());
        assertTrue(store.find("t4").isPresent());
    }

    @Test
    void concurrentWritesNeverExceedCapacityAndSpansStayWhole() throws InterruptedException {
        int capacity = 64;
        int traces = 256;
        int spansPerTrace = 10;
        TraceStore store = new InMemoryTraceStore(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(traces);

        for (int i = 0; i < traces; i++) {
            final String traceId = "trace-" + i;
            pool.submit(() -> {
                TraceRecord record = store.getOrCreate(traceId);
                for (int s = 0; s < spansPerTrace; s++) {
                    record.addSpan(new SpanRecord("span-" + s, null, traceId + "-op",
                            Thread.currentThread().getName(), 0L, 1L,
                            false, false, ErrorCategory.NONE));
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        log.info("size={} capacity={} 判定依据=size<=capacity", store.size(), capacity);
        assertTrue(store.size() <= capacity, "capacity breached");

        // 判定依据：存活追踪中的 span 要么完整（10个）要么属于同一条追踪，无跨追踪混合
        Set<String> seenOperations = new HashSet<>();
        int wholeRecords = 0;
        for (int i = 0; i < traces; i++) {
            var found = store.find("trace-" + i);
            if (found.isEmpty()) {
                continue; // 被淘汰属正常
            }
            var spans = found.get().getSpans();
            final String expectedOp = "trace-" + i + "-op";
            assertEquals(spansPerTrace, spans.size(), "half-written record for trace-" + i);
            assertTrue(spans.stream().allMatch(sp -> sp.getOperation().equals(expectedOp)),
                    "cross-trace contamination in trace-" + i);
            wholeRecords++;
            spans.forEach(sp -> seenOperations.add(sp.getOperation()));
        }
        log.info("wholeRecords={} 判定依据=所有存活记录 span 数=={} 且 operation 与 traceId 对应",
                wholeRecords, spansPerTrace);
        assertTrue(wholeRecords > 0);
    }
}

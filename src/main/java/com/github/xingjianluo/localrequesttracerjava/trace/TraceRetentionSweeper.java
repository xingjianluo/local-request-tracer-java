package com.github.xingjianluo.localrequesttracerjava.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时过期回收：周期触发存储的 TTL 清理。
 *
 * <p>规则：ttlMs &gt; 0 才实际清理；除定时触发外，写入与按标识查询也会顺带回收，
 * 因此即使两次调度之间存在间隔，也不会把已过期记录当成命中返回。
 * 清理与查询在存储的同一把锁下完成，查询侧永远拿到完整记录或明确的“没有”。
 */
public final class TraceRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionSweeper.class);

    private final TraceStore store;
    private final long ttlMs;

    public TraceRetentionSweeper(TraceStore store, long ttlMs) {
        this.store = store;
        this.ttlMs = ttlMs;
    }

    @Scheduled(fixedDelayString = "${trace.retention-sweep-ms:30000}")
    public void sweep() {
        if (ttlMs <= 0) {
            return;
        }
        int removed = store.purgeExpired(System.currentTimeMillis());
        if (removed > 0) {
            log.info("trace TTL sweep removed={} ttlMs={}", removed, ttlMs);
        }
    }
}

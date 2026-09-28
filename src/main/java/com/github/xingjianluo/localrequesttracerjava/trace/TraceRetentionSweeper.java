package com.github.xingjianluo.localrequesttracerjava.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

import java.time.Duration;

/**
 * 按时间过期回收的后台扫描器：固定延迟周期触发 {@link TraceStore#purgeExpired()}。
 *
 * <p>仅当 {@code trace.sweep-interval-ms > 0} 且 {@code trace.retention-ms > 0}
 * 时由 {@code TraceConfig} 条件注册；间隔为 0 不启动后台线程，
 * 过期回收仍会在每次写入/查询时顺带完成。
 *
 * <p>用 {@link SchedulingConfigurer} 编程式注册固定延迟，间隔来自配置对象本身，
 * 避免占位符/SpEL 对 bean 名的隐式依赖。
 */
public class TraceRetentionSweeper implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionSweeper.class);

    private final TraceStore store;
    private final Duration interval;

    public TraceRetentionSweeper(TraceStore store, long intervalMs) {
        this.store = store;
        this.interval = Duration.ofMillis(intervalMs);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::sweep, interval);
    }

    void sweep() {
        int purged = store.purgeExpired();
        if (purged > 0 && log.isDebugEnabled()) {
            log.debug("trace retention sweep purged={}", purged);
        }
    }
}

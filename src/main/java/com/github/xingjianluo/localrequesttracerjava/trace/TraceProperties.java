package com.github.xingjianluo.localrequesttracerjava.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 追踪配置。
 * <ul>
 *   <li>maxTraces：本地存储容量上限，写满后 FIFO 顶掉最早记录；</li>
 *   <li>slowThresholdMs：慢操作阈值，超过即归因 SLOW；</li>
 *   <li>retentionMs：追踪保留时长（按 trace 结束时间计），超过即过期回收；
 *       0 表示关闭按时间过期，只保留容量上限；</li>
 *   <li>sweepIntervalMs：后台过期回收的扫描间隔；0 表示不启动后台扫描，
 *       仅在写入/查询时顺带回收。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "trace")
public record TraceProperties(
        int maxTraces,
        long slowThresholdMs,
        long retentionMs,
        long sweepIntervalMs
) {

    public TraceProperties {
        if (maxTraces <= 0) {
            maxTraces = 1024;
        }
        if (slowThresholdMs <= 0) {
            slowThresholdMs = 500;
        }
        if (retentionMs < 0) {
            retentionMs = 0;
        }
        if (sweepIntervalMs < 0) {
            sweepIntervalMs = 0;
        }
    }
}

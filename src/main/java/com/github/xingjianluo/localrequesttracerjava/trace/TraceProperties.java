package com.github.xingjianluo.localrequesttracerjava.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 追踪配置。
 * maxTraces：本地存储容量上限，写满后 FIFO 淘汰；
 * slowThresholdMs：慢操作阈值，超过即归因 SLOW；
 * ttlMs：追踪保留时长，超过即按时间过期回收，<=0 表示只受容量上限约束。
 */
@ConfigurationProperties(prefix = "trace")
public record TraceProperties(int maxTraces, long slowThresholdMs, long ttlMs) {

    public TraceProperties {
        if (maxTraces <= 0) {
            maxTraces = 1024;
        }
        if (slowThresholdMs <= 0) {
            slowThresholdMs = 500;
        }
        if (ttlMs < 0) {
            ttlMs = 0;
        }
    }
}

package com.github.xingjianluo.localrequesttracerjava.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 追踪配置。
 * maxTraces：本地存储容量上限，写满后 FIFO 淘汰；
 * slowThresholdMs：慢操作阈值，超过即归因 SLOW。
 */
@ConfigurationProperties(prefix = "trace")
public record TraceProperties(int maxTraces, long slowThresholdMs) {

    public TraceProperties {
        if (maxTraces <= 0) {
            maxTraces = 1024;
        }
        if (slowThresholdMs <= 0) {
            slowThresholdMs = 500;
        }
    }
}

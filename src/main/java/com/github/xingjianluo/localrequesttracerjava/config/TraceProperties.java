package com.github.xingjianluo.localrequesttracerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 追踪相关配置。
 */
@ConfigurationProperties(prefix = "trace")
public class TraceProperties {

    /** 本地存储容量上限（条数）。 */
    private int storeCapacity = 1024;

    /** 慢操作阈值（毫秒）。 */
    private long slowThresholdMillis = 500;

    public int getStoreCapacity() { return storeCapacity; }
    public void setStoreCapacity(int storeCapacity) { this.storeCapacity = storeCapacity; }
    public long getSlowThresholdMillis() { return slowThresholdMillis; }
    public void setSlowThresholdMillis(long slowThresholdMillis) { this.slowThresholdMillis = slowThresholdMillis; }
}

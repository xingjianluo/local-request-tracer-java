package com.github.xingjianluo.localrequesttracerjava.config;

import com.github.xingjianluo.localrequesttracerjava.trace.InMemoryTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配追踪存储并把容量/慢阈值注入 TraceContext。
 */
@Configuration
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfiguration {

    private final TraceStore traceStore;
    private final TraceProperties properties;

    public TraceConfiguration(TraceStore traceStore, TraceProperties properties) {
        this.traceStore = traceStore;
        this.properties = properties;
    }

    @Bean
    public static TraceStore traceStore(TraceProperties properties) {
        return new InMemoryTraceStore(properties.getStoreCapacity());
    }

    @PostConstruct
    void configureTraceContext() {
        TraceContext.configure(traceStore, properties.getSlowThresholdMillis());
    }
}

package com.github.xingjianluo.localrequesttracerjava.config;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceProperties;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.async.TraceTaskDecorator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfig {

    @Bean
    public ErrorClassifier errorClassifier() {
        return new ErrorClassifier();
    }

    @Bean
    public TraceStore traceStore(TraceProperties properties) {
        return new BoundedTraceStore(properties.maxTraces(), properties.retentionMs(), Clock.systemUTC());
    }

    @Bean
    public Tracer tracer(TraceStore store, ErrorClassifier classifier, TraceProperties properties) {
        return new Tracer(store, classifier, properties.slowThresholdMs());
    }

    /** 仅当保留时长与扫描间隔都为正时才注册后台过期回收。 */
    @Bean
    @org.springframework.context.annotation.Conditional(TraceRetentionCondition.class)
    public com.github.xingjianluo.localrequesttracerjava.trace.TraceRetentionSweeper traceRetentionSweeper(
            TraceStore store, TraceProperties properties) {
        return new com.github.xingjianluo.localrequesttracerjava.trace.TraceRetentionSweeper(
                store, properties.sweepIntervalMs());
    }

    @Bean
    public TaskDecorator traceTaskDecorator() {
        return new TraceTaskDecorator();
    }

    /** 应用主异步执行器：挂追踪装饰器，@Async 与注入 Executor 的异步任务共用。 */
    @Bean
    @Primary
    public Executor tracingTaskExecutor(TaskDecorator traceTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(traceTaskDecorator);
        executor.initialize();
        return executor;
    }
}

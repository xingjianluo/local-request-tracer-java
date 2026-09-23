package com.github.xingjianluo.localrequesttracerjava.web.demo;

import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 演示用服务：同步与异步子操作。
 * 异步方法经 TraceTaskDecorator 传播上下文，归属同一条追踪链。
 */
@Service
public class DemoService {

    private final Tracer tracer;

    public DemoService(Tracer tracer) {
        this.tracer = tracer;
    }

    public String syncWork(String tag) {
        return tracer.call("sync-work-" + tag, "INTERNAL", () -> "ok");
    }

    @Async
    public CompletableFuture<String> asyncWork(String tag) {
        String result = tracer.call("async-work-" + tag, "INTERNAL", () -> "ok");
        return CompletableFuture.completedFuture(result);
    }
}

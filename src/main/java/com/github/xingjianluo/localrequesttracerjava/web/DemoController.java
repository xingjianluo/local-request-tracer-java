package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceExecutors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 演示入口：同步、异步、失败、慢操作。
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final ExecutorService executor =
            TraceExecutors.tracePropagating(Executors.newFixedThreadPool(4));

    @GetMapping("/ok")
    public Map<String, Object> ok() {
        TraceContext.startSpan("demo.ok.work");
        try {
            return Map.of("traceId", String.valueOf(TraceContext.currentTraceId()), "result", "ok");
        } finally {
            TraceContext.endSpan();
        }
    }

    @GetMapping("/async")
    public Map<String, Object> async() throws InterruptedException {
        TraceContext.startSpan("demo.async.parent");
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<String> childTraceIds = new AtomicReference<>("");
        try {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                executor.submit(() -> {
                    TraceContext.startSpan("demo.async.child-" + idx);
                    try {
                        childTraceIds.updateAndGet(s -> s + TraceContext.currentTraceId() + ";");
                    } finally {
                        TraceContext.endSpan();
                        latch.countDown();
                    }
                });
            }
            latch.await(5, TimeUnit.SECONDS);
            return Map.of("traceId", String.valueOf(TraceContext.currentTraceId()),
                    "childTraceIds", childTraceIds.get());
        } finally {
            TraceContext.endSpan();
        }
    }

    @GetMapping("/fail")
    public Map<String, Object> fail(@RequestParam(defaultValue = "validation") String kind) {
        TraceContext.startSpan("demo.fail.work");
        try {
            switch (kind) {
                case "timeout" -> throw new RuntimeException(new java.util.concurrent.TimeoutException("simulated timeout"));
                case "io" -> throw new RuntimeException(new java.io.IOException("simulated io failure"));
                default -> throw new IllegalArgumentException("simulated validation failure");
            }
        } catch (RuntimeException e) {
            // 异常交给 endSpan 归一化分类，再原样抛出由全局异常处理兜底。
            TraceContext.endSpan(e);
            throw e;
        }
    }

    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "800") long millis) throws InterruptedException {
        TraceContext.startSpan("demo.slow.work");
        try {
            Thread.sleep(millis);
            return Map.of("traceId", String.valueOf(TraceContext.currentTraceId()), "sleptMillis", millis);
        } finally {
            TraceContext.endSpan();
        }
    }
}

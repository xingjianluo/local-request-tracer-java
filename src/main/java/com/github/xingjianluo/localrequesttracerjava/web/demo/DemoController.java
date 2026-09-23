package com.github.xingjianluo.localrequesttracerjava.web.demo;

import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.context.TraceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 演示入口：正常 / 慢操作 / 各类失败，用于触发并验证追踪与归因。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoService demoService;
    private final Tracer tracer;

    public DemoController(DemoService demoService, Tracer tracer) {
        this.demoService = demoService;
        this.tracer = tracer;
    }

    /** 正常链路：同步子操作 + 异步子操作，归属同一 trace。 */
    @GetMapping("/ok")
    public Map<String, Object> ok() throws ExecutionException, InterruptedException {
        demoService.syncWork("a");
        CompletableFuture<String> async = demoService.asyncWork("b");
        async.get();
        return Map.of("result", "ok", "traceId", String.valueOf(TraceContext.currentTraceId()));
    }

    /** 慢操作：子 span 睡眠超过阈值，归因 SLOW。 */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "600") long ms) {
        tracer.run("slow-block", "INTERNAL", () -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return Map.of("result", "ok", "traceId", String.valueOf(TraceContext.currentTraceId()));
    }

    /** 失败入口：按 type 抛出不同异常，验证错误归一化。 */
    @GetMapping("/fail")
    public Map<String, Object> fail(@RequestParam(defaultValue = "validation") String type) throws Exception {
        switch (type) {
            case "validation" -> throw new IllegalArgumentException("demo invalid argument");
            case "conflict" -> throw new IllegalStateException("demo state conflict");
            case "timeout" -> throw new TimeoutException("demo downstream timeout");
            case "upstream" -> throw new ConnectException("demo connect refused");
            case "io" -> throw new IOException("demo io failure");
            case "unknown" -> throw new Exception("demo checked failure");
            default -> throw new RuntimeException("demo internal failure");
        }
    }
}

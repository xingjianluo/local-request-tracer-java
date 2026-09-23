package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.SpanRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceRecord;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 traceId 查询本地保存的追踪记录。输出只含结构化字段，不含堆栈。
 */
@RestController
@RequestMapping("/traces")
public class TraceQueryController {

    private final TraceStore traceStore;

    public TraceQueryController(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/{traceId}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable String traceId) {
        return traceStore.find(traceId)
                .map(record -> ResponseEntity.ok(toView(record)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toView(TraceRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("traceId", record.getTraceId());
        List<Map<String, Object>> spans = record.getSpans().stream().map(this::toSpanView).toList();
        view.put("spans", spans);
        view.put("spanCount", spans.size());
        return view;
    }

    private Map<String, Object> toSpanView(SpanRecord span) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("spanId", span.getSpanId());
        view.put("parentSpanId", span.getParentSpanId());
        view.put("operation", span.getOperation());
        view.put("thread", span.getThreadName());
        view.put("startEpochMillis", span.getStartEpochMillis());
        view.put("durationMillis", span.getDurationNanos() / 1_000_000);
        view.put("failed", span.isFailed());
        view.put("slow", span.isSlow());
        view.put("errorCategory", span.getErrorCategory().name());
        return view;
    }
}

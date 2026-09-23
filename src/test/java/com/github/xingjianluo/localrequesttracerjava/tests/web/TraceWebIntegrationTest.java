package com.github.xingjianluo.localrequesttracerjava.tests.web;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路集成：响应头携带 traceId、异步子操作归属同一条追踪、
 * 错误类别可区分且不暴露堆栈、慢操作被标记、追踪可按标识查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceWebIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TraceWebIntegrationTest.class);

    @Autowired
    private TestRestTemplate rest;

    @Test
    void okRequestProducesQueryableTrace() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/ok", Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        log.info("traceId={} 判定依据=响应头存在且 /traces/{id} 可查到 span", traceId);
        assertNotNull(traceId, "X-Trace-Id header missing");

        Map<String, Object> trace = queryTrace(traceId);
        List<Map<String, Object>> spans = spans(trace);
        assertFalse(spans.isEmpty(), "no spans recorded");
        assertTrue(spans.stream().anyMatch(s -> Objects.equals(s.get("operation"), "GET /demo/ok")));
    }

    @Test
    void asyncChildrenShareSameTrace() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/async", Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(traceId);

        String childTraceIds = String.valueOf(Objects.requireNonNull(resp.getBody()).get("childTraceIds"));
        log.info("traceId={} childTraceIds={} 判定依据=子线程 traceId 与请求 traceId 一致", traceId, childTraceIds);
        for (String part : childTraceIds.split(";")) {
            if (!part.isBlank()) {
                assertEquals(traceId, part, "async child attached to different trace");
            }
        }

        Map<String, Object> trace = queryTrace(traceId);
        List<Map<String, Object>> spans = spans(trace);
        long childSpans = spans.stream()
                .filter(s -> String.valueOf(s.get("operation")).startsWith("demo.async.child"))
                .count();
        log.info("traceId={} childSpanCount={} 判定依据=异步子 span 数==2 且 parentSpanId 非空", traceId, childSpans);
        assertEquals(2, childSpans);
        spans.stream()
                .filter(s -> String.valueOf(s.get("operation")).startsWith("demo.async.child"))
                .forEach(s -> assertNotNull(s.get("parentSpanId"), "child span lost parent linkage"));
    }

    @Test
    void failuresAreClassifiedAndDistinguishable() {
        String validation = failAndGetCategory("/demo/fail?kind=validation");
        String timeout = failAndGetCategory("/demo/fail?kind=timeout");
        String io = failAndGetCategory("/demo/fail?kind=io");
        log.info("validation={} timeout={} io={} 判定依据=三者类别互不相同", validation, timeout, io);
        assertEquals("VALIDATION", validation);
        assertEquals("TIMEOUT", timeout);
        assertEquals("IO", io);
    }

    @Test
    void errorResponseDoesNotLeakInternals() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/fail?kind=io", Map.class);
        String body = String.valueOf(resp.getBody());
        log.info("traceId={} body={} 判定依据=响应体不含堆栈/异常类名等内部细节",
                resp.getHeaders().getFirst("X-Trace-Id"), body);
        assertFalse(body.contains("Exception"), "stack/class name leaked: " + body);
        assertFalse(body.contains("at com."), "stack trace leaked: " + body);
        assertTrue(resp.getStatusCode().is5xxServerError() || resp.getStatusCode().is4xxClientError());
    }

    @Test
    void slowOperationIsFlagged() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/slow?millis=800", Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        Map<String, Object> trace = queryTrace(traceId);
        List<Map<String, Object>> spans = spans(trace);
        boolean slowFlagged = spans.stream().anyMatch(s -> Boolean.TRUE.equals(s.get("slow")));
        log.info("traceId={} slowFlagged={} 判定依据=耗时800ms>阈值500ms 的 span 被标记 slow", traceId, slowFlagged);
        assertTrue(slowFlagged, "slow span not flagged");
    }

    private String failAndGetCategory(String path) {
        ResponseEntity<Map> resp = rest.getForEntity(path, Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        // 判定依据：响应体类别与追踪库中失败 span 的类别一致
        Map<String, Object> trace = queryTrace(traceId);
        String spanCategory = spans(trace).stream()
                .filter(s -> Boolean.TRUE.equals(s.get("failed")))
                .map(s -> String.valueOf(s.get("errorCategory")))
                .findFirst().orElse("NONE");
        log.info("traceId={} bodyCategory={} spanCategory={} 判定依据=两者一致", traceId, body.get("error"), spanCategory);
        assertEquals(String.valueOf(body.get("error")), spanCategory,
                "response category and recorded span category diverge");
        return String.valueOf(body.get("error"));
    }

    private Map<String, Object> queryTrace(String traceId) {
        ResponseEntity<Map> resp = rest.getForEntity("/traces/" + traceId, Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "trace not found: " + traceId);
        return Objects.requireNonNull(resp.getBody());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> spans(Map<String, Object> trace) {
        return (List<Map<String, Object>>) trace.get("spans");
    }
}

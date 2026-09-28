package com.github.xingjianluo.localrequesttracerjava.tests.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceDetail;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.web.TraceFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：HTTP 入口 → 追踪链 → 错误归一化 → 按 traceId 查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TraceIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String hitAndGetTraceId(String uri) throws Exception {
        MvcResult result = mockMvc.perform(get(uri)).andReturn();
        String traceId = result.getResponse().getHeader(TraceFilter.TRACE_ID_HEADER);
        assertNotNull(traceId, "响应必须携带 X-Trace-Id");
        return traceId;
    }

    private TraceSnapshot queryTrace(String traceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/traces/{id}", traceId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TraceDetail.class).trace();
    }

    @Test
    void okRequestFormsOneTraceWithAsyncChild() throws Exception {
        String traceId = hitAndGetTraceId("/api/demo/ok");
        TraceSnapshot trace = queryTrace(traceId);

        List<String> kinds = trace.spans().stream().map(SpanData::kind).toList();
        log.info("integration traceId={} spans={} kinds={} status={} basis=同步+异步子操作应归属同一trace",
                traceId, trace.spans().size(), kinds, trace.status());

        assertEquals("SUCCESS", trace.status().name());
        assertTrue(trace.spans().stream().anyMatch(s -> "SERVER".equals(s.kind())), "必须有 SERVER 根 span");
        assertTrue(trace.spans().stream().anyMatch(s -> s.name().startsWith("async-work")),
                "异步子操作必须归属同一 trace");
        Map<String, SpanData> byId = new HashMap<>();
        trace.spans().forEach(s -> byId.put(s.spanId(), s));
        for (SpanData span : trace.spans()) {
            if (span.parentSpanId() != null) {
                assertTrue(byId.containsKey(span.parentSpanId()),
                        "父 span 必须在同一 trace 内: " + span.name());
            }
        }
    }

    @Test
    void slowSpanIsAttributed() throws Exception {
        String traceId = hitAndGetTraceId("/api/demo/slow?ms=600");
        TraceSnapshot trace = queryTrace(traceId);
        SpanData slow = trace.spans().stream()
                .filter(s -> "slow-block".equals(s.name()))
                .findFirst().orElseThrow();
        log.info("slow attribution traceId={} span={} durationMs={} latencyClass={} basis=超过阈值500ms→SLOW",
                traceId, slow.name(), slow.durationMs(), slow.latencyClass());
        assertEquals("SLOW", slow.latencyClass().name(), "超过阈值必须归因 SLOW");
    }

    @Test
    void failuresReturnNormalizedCategoryWithoutInternals() throws Exception {
        MvcResult r1 = mockMvc.perform(get("/api/demo/fail?type=validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION_ERROR"))
                .andReturn();
        MvcResult r2 = mockMvc.perform(get("/api/demo/fail?type=timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.category").value("TIMEOUT"))
                .andReturn();

        String body1 = r1.getResponse().getContentAsString();
        String body2 = r2.getResponse().getContentAsString();
        String traceId1 = r1.getResponse().getHeader(TraceFilter.TRACE_ID_HEADER);
        String traceId2 = r2.getResponse().getHeader(TraceFilter.TRACE_ID_HEADER);
        log.info("error normalization traceId={} body={} basis=参数异常→VALIDATION_ERROR/400", traceId1, body1);
        log.info("error normalization traceId={} body={} basis=超时→TIMEOUT/504", traceId2, body2);

        assertFalse(body1.contains("Exception"), "对外错误体不得暴露异常类名");
        assertFalse(body1.contains("demo invalid"), "对外错误体不得暴露异常消息");
        assertFalse(body2.contains("Exception"), "对外错误体不得暴露异常类名");

        TraceSnapshot t1 = queryTrace(traceId1);
        TraceSnapshot t2 = queryTrace(traceId2);
        assertEquals("FAILED", t1.status().name(), "失败不得静默丢失");
        assertEquals("FAILED", t2.status().name());
        assertTrue(t1.spans().stream().anyMatch(s -> "VALIDATION_ERROR".equals(String.valueOf(s.errorCategory()))));
        assertTrue(t2.spans().stream().anyMatch(s -> "TIMEOUT".equals(String.valueOf(s.errorCategory()))));
    }

    @Test
    void unknownTraceIdReturns404() throws Exception {
        mockMvc.perform(get("/api/traces/{id}", "nonexistent"))
                .andExpect(status().isNotFound());
    }
}

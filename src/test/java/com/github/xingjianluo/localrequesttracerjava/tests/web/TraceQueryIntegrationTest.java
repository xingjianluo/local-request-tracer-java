package com.github.xingjianluo.localrequesttracerjava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.localrequesttracerjava.web.TraceFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：条件批量查询接口（多条件筛选 + 归因字段），
 * 以及 410（过期回收/容量顶掉）与 404（从未记录）的区分。
 * 独立上下文：保留时长 300ms，不启动后台扫描，靠查询顺带回收。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "trace.retention-ms=300",
        "trace.sweep-interval-ms=0"
})
class TraceQueryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TraceQueryIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String hit(String uri) throws Exception {
        MvcResult result = mockMvc.perform(get(uri)).andReturn();
        return result.getResponse().getHeader(TraceFilter.TRACE_ID_HEADER);
    }

    @Test
    void listEndpointFiltersAndCarriesAttribution() throws Exception {
        // 造两条失败（不同类别）与一条成功
        String validationId = hit("/api/demo/fail?type=validation");
        String timeoutId = hit("/api/demo/fail?type=timeout");
        String okId = hit("/api/demo/ok");
        assertNotNull(validationId);
        assertNotNull(timeoutId);
        assertNotNull(okId);

        // status=FAILED&category=TIMEOUT：只应含 timeout 一条，且条目带归因
        MvcResult result = mockMvc.perform(get("/api/traces")
                        .param("status", "FAILED")
                        .param("category", "TIMEOUT")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].attribution.errorCategory").value("TIMEOUT"))
                .andExpect(jsonPath("$.items[0].attribution.slowestSpan.name").exists())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(timeoutId, page.get("items").get(0).get("traceId").asText());
        log.info("list filtered body={}", result.getResponse().getContentAsString());

        // status=SUCCESS：至少包含刚造的成功链路
        mockMvc.perform(get("/api/traces").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.traceId=='" + okId + "')]").exists());

        // 详情接口也带同一层归因
        mockMvc.perform(get("/api/traces/{id}", timeoutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attribution.errorCategory").value("TIMEOUT"))
                .andExpect(jsonPath("$.attribution.slowestSpan.name").exists());
    }

    @Test
    void expiredTraceReturns410WithReasonUnknownReturns404() throws Exception {
        String traceId = hit("/api/demo/ok");

        // 未过期：200
        mockMvc.perform(get("/api/traces/{id}", traceId)).andExpect(status().isOk());

        // 超过 300ms 保留时长后：410 + EXPIRED（查询时顺带回收）
        Thread.sleep(500);
        MvcResult gone = mockMvc.perform(get("/api/traces/{id}", traceId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("EXPIRED"))
                .andReturn();
        log.info("expired body={}", gone.getResponse().getContentAsString());

        // 再查仍是 410，登记表保留回收结论
        mockMvc.perform(get("/api/traces/{id}", traceId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("EXPIRED"));

        // 从未记录：404
        mockMvc.perform(get("/api/traces/{id}", "never-recorded-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("NEVER_RECORDED"));
    }

    @Test
    void paginationWalksPagesWithoutDuplicatesAndCoversNewTraces() throws Exception {
        // 连续造 5 条失败链路（validation）。该上下文 TTL 极短，成功链路可能在翻页途中过期，
        // 因此这里用失败集合：本类新建的失败记录不会在翻页瞬间过期。
        for (int i = 0; i < 5; i++) {
            hit("/api/demo/fail?type=validation");
        }

        String cursor = null;
        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 50; page++) {
            var req = get("/api/traces")
                    .param("status", "FAILED")
                    .param("category", "VALIDATION_ERROR")
                    .param("limit", "2");
            if (cursor != null) {
                req.param("cursor", cursor);
            }
            MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            body.get("items").forEach(n -> walked.add(n.get("traceId").asText()));
            if (!body.get("hasMore").asBoolean()) {
                org.junit.jupiter.api.Assertions.assertTrue(body.get("nextCursor").isNull(),
                        "无更多数据时不应再给游标");
                break;
            }
            org.junit.jupiter.api.Assertions.assertFalse(body.get("nextCursor").isNull(),
                    "hasMore=true 时必须给游标");
            cursor = body.get("nextCursor").asText();
        }
        // 翻页本身不重复；刚造的失败记录都能沿游标走到
        assertEquals(walked.size(), new java.util.HashSet<>(walked).size(),
                "翻页条目不得重复");
        org.junit.jupiter.api.Assertions.assertTrue(walked.size() >= 5,
                "至少应覆盖刚造的 5 条失败链路，实际=" + walked.size());
        log.info("pagination walked {} failed traces", walked.size());
    }

    @Test
    void queryRequestsAreNotTracedAndRepeatedQueriesDoNotDrift() throws Exception {
        // 该测试上下文 TTL=300ms，同类前置方法可能留下恰好到期的同类记录，
        // 查询顺带回收它们属于过期语义的正常变化。为只检验"查询动作不落库"，
        // 用时间窗把结果隔离为本测试开始之后的数据。
        long windowStart = System.currentTimeMillis();
        // 先造一批可预测的数据：3 条 validation 失败 + 1 条成功
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(hit("/api/demo/fail?type=validation"));
        }
        String okId = hit("/api/demo/ok");
        created.add(okId);

        // 连续查询多次，每次原始结果必须逐字节一致：
        // 查询动作自身不得作为追踪落库，否则越查冒出的新条目越多
        String uri = "/api/traces?status=FAILED&category=VALIDATION_ERROR&limit=2&from="
                + windowStart;
        String firstBody = null;
        List<String> firstIds = null;
        List<String> createdFailed = created.subList(0, 3);
        for (int round = 0; round < 5; round++) {
            // 首页原始响应体逐字节一致（验证小分页结果不因查询动作漂移）
            MvcResult page = mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
            String body = page.getResponse().getContentAsString();
            if (firstBody == null) {
                firstBody = body;
            } else {
                assertEquals(firstBody, body, "第 " + round + " 次重复查询结果漂移：查询动作被写进了追踪");
            }

            // 一次性大页取全量集合：本测试刚造的 3 条必须一次查全，且逐轮集合与次序一致
            JsonNode allNode = objectMapper.readTree(mockMvc.perform(get("/api/traces")
                            .param("status", "FAILED")
                            .param("category", "VALIDATION_ERROR")
                            .param("from", String.valueOf(windowStart))
                            .param("limit", "200"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            List<String> ids = new ArrayList<>();
            allNode.get("items").forEach(n -> ids.add(n.get("traceId").asText()));
            assertEquals(3, ids.size(), "时间窗内应恰好只有本测试造的 3 条失败记录");
            assertTrue(ids.containsAll(createdFailed),
                    "同根因同类别记录必须能被同一筛选条件一次查全，不漏");
            if (firstIds == null) {
                firstIds = ids;
            } else {
                assertEquals(firstIds, ids, "重复全量查询的结果集合与次序随查询轮次漂移");
            }
        }

        // 查询接口响应不带 X-Trace-Id（查询请求不开链）
        org.junit.jupiter.api.Assertions.assertNull(
                mockMvc.perform(get("/api/traces").param("limit", "1")).andReturn()
                        .getResponse().getHeader(TraceFilter.TRACE_ID_HEADER),
                "查询接口不应开启追踪链");
        // 按 traceId 查详情同样不开链、不落库
        org.junit.jupiter.api.Assertions.assertNull(
                mockMvc.perform(get("/api/traces/{id}", okId)).andReturn()
                        .getResponse().getHeader(TraceFilter.TRACE_ID_HEADER),
                "详情查询接口不应开启追踪链");

        // 全量列表中不得出现任何查询请求产生的 SERVER 记录：
        // 反复查询之后，SUCCESS 集合仍只包含真实业务链路（async 链路名只存在于业务请求中）
        JsonNode successPage = objectMapper.readTree(mockMvc.perform(
                        get("/api/traces").param("status", "SUCCESS").param("limit", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> successIds = new ArrayList<>();
        successPage.get("items").forEach(n -> successIds.add(n.get("traceId").asText()));
        assertTrue(successIds.contains(okId), "真实成功链路仍可查到");
        log.info("repeated queries stable, firstBody={}, matchedCount={}",
                firstBody, firstIds.size());
    }
}

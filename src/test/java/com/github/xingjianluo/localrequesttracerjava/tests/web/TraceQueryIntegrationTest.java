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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        // 用测试开始时刻做时间窗下界，与同类其他用例写入的记录隔离（共享同一存储/上下文）
        long since = System.currentTimeMillis() - 1;
        // 造两条失败（不同类别）与一条成功
        String validationId = hit("/api/demo/fail?type=validation");
        String timeoutId = hit("/api/demo/fail?type=timeout");
        String okId = hit("/api/demo/ok");
        assertNotNull(validationId);
        assertNotNull(timeoutId);
        assertNotNull(okId);

        // status=FAILED&category=TIMEOUT：只应含 timeout 一条，且条目带归因
        MvcResult result = mockMvc.perform(get("/api/traces")
                        .param("from", String.valueOf(since))
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
    void queryRequestsAreNotTracedAndRepeatedQueriesDoNotDrift() throws Exception {
        // 用测试开始时刻做时间窗下界，与同类其他用例写入的记录隔离（共享同一存储/上下文）
        long since = System.currentTimeMillis() - 1;
        // 固定一批基准数据：2 条失败（TIMEOUT）+ 1 条成功；全部在短 TTL 内快速查询完
        String t1 = hit("/api/demo/fail?type=timeout");
        String t2 = hit("/api/demo/fail?type=timeout");
        String ok = hit("/api/demo/ok");
        assertNotNull(t1);
        assertNotNull(t2);
        assertNotNull(ok);

        // 1) 查询请求本身不得开追踪链：不回写 X-Trace-Id
        MvcResult listResult = mockMvc.perform(get("/api/traces"))
                .andExpect(status().isOk())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertNull(
                listResult.getResponse().getHeader(TraceFilter.TRACE_ID_HEADER),
                "列表查询不得回写 X-Trace-Id（查询不被追踪）");
        MvcResult detailResult = mockMvc.perform(get("/api/traces/{id}", t1))
                .andExpect(status().isOk())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertNull(
                detailResult.getResponse().getHeader(TraceFilter.TRACE_ID_HEADER),
                "详情查询同样不得开追踪链");

        // 2) 同一条件连续重复查询：结果逐字节一致，不会越查越多
        String baseline = mockMvc.perform(get("/api/traces")
                        .param("from", String.valueOf(since))
                        .param("status", "FAILED")
                        .param("category", "TIMEOUT")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        for (int i = 0; i < 5; i++) {
            String body = mockMvc.perform(get("/api/traces")
                            .param("from", String.valueOf(since))
                            .param("status", "FAILED")
                            .param("category", "TIMEOUT")
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andReturn().getResponse().getContentAsString();
            assertEquals(baseline, body,
                    "第 " + i + " 次重复查询必须与首次一致，查询动作不得产生新追踪");
        }

        // 3) 时间窗内全量列表：经历上面若干次查询后，总数必须正好等于基准三条，查询不得混入
        JsonNode page = objectMapper.readTree(
                mockMvc.perform(get("/api/traces")
                        .param("from", String.valueOf(since))
                        .param("limit", "200"))
                        .andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(n -> ids.add(n.get("traceId").asText()));
        assertTrue(ids.contains(t1) && ids.contains(t2) && ids.contains(ok),
                "基准三条记录都应在列");
        assertEquals(3, ids.size(),
                "查询动作不得被当成追踪写进结果，实际=" + ids);
        log.info("repeated query stable, traceCount={}", ids.size());
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
    void repeatedQueriesAndPaginationStayStableWhileBackgroundWritesContinue() throws Exception {
        // 时间窗 [since, windowEnd] 内固定 6 条 VALIDATION_ERROR 基准记录；
        // 后台持续写入的新记录结束时间都在 windowEnd 之后，且类别为 TIMEOUT，双重隔离。
        long since = System.currentTimeMillis() - 1;
        java.util.Set<String> baseline = new java.util.HashSet<>();
        for (int i = 0; i < 6; i++) {
            baseline.add(hit("/api/demo/fail?type=validation"));
        }
        long windowEnd = System.currentTimeMillis() + 1;

        CountDownLatch stop = new CountDownLatch(1);
        AtomicReference<Throwable> writerError = new AtomicReference<>();
        ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "background-trace-writer");
            t.setDaemon(true);
            return t;
        });
        writer.submit(() -> {
            try {
                while (stop.getCount() > 0) {
                    try {
                        // MockMvc 线程安全；后台写的是窗口外、另一类别的业务追踪
                        mockMvc.perform(get("/api/demo/fail?type=timeout")).andReturn();
                        Thread.yield();
                    } catch (Exception e) {
                        writerError.set(e);
                        return;
                    }
                }
            } catch (Throwable t) {
                writerError.set(t);
            }
        });

        try {
            // 1) 窗口内同条件重复查询：条目集合每次一致，不受后台写入/查询动作影响
            List<String> firstPage = null;
            for (int round = 0; round < 20; round++) {
                MvcResult result = mockMvc.perform(get("/api/traces")
                                .param("from", String.valueOf(since))
                                .param("to", String.valueOf(windowEnd))
                                .param("status", "FAILED")
                                .param("category", "VALIDATION_ERROR")
                                .param("limit", "50"))
                        .andExpect(status().isOk()).andReturn();
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                List<String> ids = new ArrayList<>();
                body.get("items").forEach(n -> ids.add(n.get("traceId").asText()));
                assertEquals(6, ids.size(), "窗口内基准记录数必须稳定，round=" + round);
                org.junit.jupiter.api.Assertions.assertTrue(ids.containsAll(baseline),
                        "窗口内必须始终覆盖全部基准记录");
                if (firstPage == null) {
                    firstPage = ids;
                } else {
                    assertEquals(firstPage, ids, "后台写入期间同条件结果次序不得漂移");
                }
            }

            // 2) 窗口内游标分页在后台写入下不重不漏，拼起来等于全量
            String cursor = null;
            List<String> walked = new ArrayList<>();
            for (int page = 0; page < 20; page++) {
                var req = get("/api/traces")
                        .param("from", String.valueOf(since))
                        .param("to", String.valueOf(windowEnd))
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
                    break;
                }
                cursor = body.get("nextCursor").asText();
            }
            assertEquals(6, walked.size(), "窗口内分页拼接必须恰好覆盖 6 条基准记录");
            assertEquals(6, new java.util.HashSet<>(walked).size(), "分页不得重复");
            org.junit.jupiter.api.Assertions.assertTrue(walked.containsAll(baseline),
                    "分页不得漏掉任一基准记录");
            assertEquals(firstPage, walked, "分页拼接次序与一次性查询一致");
        } finally {
            stop.countDown();
            writer.shutdown();
            org.junit.jupiter.api.Assertions.assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
        }
        org.junit.jupiter.api.Assertions.assertNull(writerError.get(), "后台写入线程不应出错");
        log.info("stable queries/pagination under background writes, windowCount=6");
    }
}

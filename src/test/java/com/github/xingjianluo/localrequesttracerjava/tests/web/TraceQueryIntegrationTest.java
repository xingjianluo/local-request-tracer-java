package com.github.xingjianluo.localrequesttracerjava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindResult;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQueryService;
import com.github.xingjianluo.localrequesttracerjava.web.GlobalExceptionHandler;
import com.github.xingjianluo.localrequesttracerjava.web.TraceController;
import com.github.xingjianluo.localrequesttracerjava.web.TraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 查询端点：
 * <ul>
 *   <li>完整 Spring 上下文：列表多条件筛选、归因字段、400 参数校验（端到端）；</li>
 *   <li>纯 MockMvc 切片：详情 410(EXPIRED/EVICTED) 与 404(UNKNOWN) 的状态映射，
 *       不启动容器、不共享存储，避免短 TTL 配置污染其他集成测试。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceQueryIntegrationTest {

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
        // 造数：1 成功、1 超时失败
        hit("/api/demo/ok");
        String timeoutId = hit("/api/demo/fail?type=timeout");

        // 只看失败 + TIMEOUT 类别
        MvcResult result = mockMvc.perform(get("/api/traces")
                        .param("status", "FAILED")
                        .param("errorCategory", "TIMEOUT")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(page.get("items").size() >= 1, "超时失败链路必须被筛出");
        for (JsonNode item : page.get("items")) {
            assertEquals("FAILED", item.at("/attribution/status").asText());
            assertEquals("TIMEOUT", item.at("/attribution/errorCategory").asText());
            assertNotNull(item.at("/attribution/slowestSpanName").asText(null));
        }

        // 成功结果不匹配失败类别
        mockMvc.perform(get("/api/traces")
                        .param("status", "SUCCESS")
                        .param("errorCategory", "TIMEOUT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // 详情接口带归因且 200
        mockMvc.perform(get("/api/traces/{id}", timeoutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attribution.errorCategory").value("TIMEOUT"))
                .andExpect(jsonPath("$.attribution.slowestSpanName").exists());
    }

    @Test
    void unknownIdReturns404AndBadEnumReturns400() throws Exception {
        mockMvc.perform(get("/api/traces/{id}", "totally-unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNKNOWN"));

        mockMvc.perform(get("/api/traces").param("status", "NONSENSE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredAndEvictedMapTo410ViaStandaloneMvc() throws Exception {
        // 手写替身，避免对 mock 框架字节码插装的依赖
        TraceQueryService service = new TraceQueryService(null) {
            @Override
            public FindResult findDetailed(String traceId) {
                return switch (traceId) {
                    case "gone-ttl" -> FindResult.missing(FindStatus.EXPIRED);
                    case "gone-cap" -> FindResult.missing(FindStatus.EVICTED);
                    default -> FindResult.missing(FindStatus.UNKNOWN);
                };
            }

            @Override
            public TracePage query(TraceQuery query) {
                return new TracePage(List.of(), null, false);
            }
        };

        MockMvc standalone = MockMvcBuilders.standaloneSetup(new TraceController(service))
                .setControllerAdvice(new GlobalExceptionHandler(null, null))
                .build();

        standalone.perform(get("/api/traces/{id}", "gone-ttl"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("EXPIRED"));
        standalone.perform(get("/api/traces/{id}", "gone-cap"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("EVICTED"));
        standalone.perform(get("/api/traces/{id}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNKNOWN"));
    }
}

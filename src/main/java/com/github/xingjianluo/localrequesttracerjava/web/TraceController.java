package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindResult;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceDetail;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 追踪查询：按标识取详情，按条件批量查最近的追踪。
 *
 * <p>详情语义：命中 200（含一层归因）；已过期回收或被容量淘汰 410，
 * 响应体 {@code reason} 区分 EXPIRED / EVICTED；从未记录 404。
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceQueryService queryService;

    public TraceController(TraceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{traceId}")
    public ResponseEntity<?> get(@PathVariable String traceId) {
        FindResult result = queryService.findDetailed(traceId);
        if (result.status() == FindStatus.FOUND) {
            TraceDetail detail = result.optionalDetail().orElseThrow();
            return ResponseEntity.ok(detail);
        }
        if (result.status() == FindStatus.UNKNOWN) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("reason", "UNKNOWN", "message", "未找到该追踪记录"));
        }
        String reason = result.status().name();
        String message = result.status() == FindStatus.EXPIRED
                ? "该追踪已超过保留时长并被回收"
                : "该追踪因容量上限已被淘汰";
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("reason", reason, "message", message));
    }

    /**
     * 条件批量查询。
     *
     * @param from 开始时间下界（epoch 毫秒，按 trace 开始时间，闭区间）
     * @param to   开始时间上界（epoch 毫秒，闭区间）
     * @param status 整体结果：SUCCESS / FAILED
     * @param category 失败最终归类（ErrorCategory 名），仅在失败结果中匹配
     * @param limit 单页条数（1..500，默认 100）
     * @param cursor 上一页返回的不透明游标
     */
    @GetMapping
    public TracePage list(@RequestParam(required = false) Long from,
                          @RequestParam(required = false) Long to,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false, name = "errorCategory") String category,
                          @RequestParam(required = false) Integer limit,
                          @RequestParam(required = false) String cursor) {
        TraceQuery query;
        try {
            query = new TraceQuery(
                    from,
                    to,
                    parseStatus(status),
                    parseCategory(category),
                    limit,
                    cursor
            );
        } catch (IllegalArgumentException e) {
            throw new InvalidTraceQueryException("invalid trace query parameter: " + e.getMessage(), e);
        }
        try {
            return queryService.query(query);
        } catch (IllegalArgumentException e) {
            throw new InvalidTraceQueryException("invalid trace query: " + e.getMessage(), e);
        }
    }

    private SpanStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SpanStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidTraceQueryException("unknown status: " + status);
        }
    }

    private ErrorCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return ErrorCategory.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidTraceQueryException("unknown errorCategory: " + category);
        }
    }
}

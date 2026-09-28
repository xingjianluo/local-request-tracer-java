package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.LookupStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceLookup;
import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 追踪查询：按条件批量查最近追踪、按标识查完整链路。
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final Tracer tracer;

    public TraceController(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 条件批量查询。
     *
     * @param from   时间窗起点（epoch 毫秒，按结束时间，含边界）
     * @param to     时间窗终点（epoch 毫秒，含边界）
     * @param status 整体结果：SUCCESS / FAILED，缺省不过滤
     * @param category 失败类别，仅在 status=FAILED 下有意义
     * @param limit  单页条数，最大 {@link TraceQuery#MAX_LIMIT}
     * @param cursor 上一页返回的不透明游标
     */
    @GetMapping
    public TracePage list(
            @RequestParam(name = "from", required = false, defaultValue = "0") long from,
            @RequestParam(name = "to", required = false, defaultValue = "0") long to,
            @RequestParam(name = "status", required = false) SpanStatus status,
            @RequestParam(name = "category", required = false) ErrorCategory category,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        TraceQuery query = new TraceQuery(from, to, status, category, limit, cursor);
        return tracer.query(query);
    }

    /**
     * 按标识查完整链路：
     * 200 完整记录；410 曾记录但已回收（body 说明 EXPIRED/EVICTED）；404 从未记录。
     */
    @GetMapping("/{traceId}")
    public ResponseEntity<?> get(@PathVariable String traceId) {
        TraceLookup lookup = tracer.lookup(traceId);
        if (lookup.isFound()) {
            TraceSnapshot snapshot = lookup.snapshot();
            return ResponseEntity.ok(snapshot);
        }
        if (lookup.status() == LookupStatus.EXPIRED || lookup.status() == LookupStatus.EVICTED) {
            String reason = lookup.status().name();
            String message = lookup.status() == LookupStatus.EXPIRED
                    ? "该追踪已超过保留时长并被回收"
                    : "该追踪已因容量上限被顶掉";
            return ResponseEntity.status(410).body(new TraceGoneBody(traceId, reason, message));
        }
        return ResponseEntity.status(404).body(Map.of(
                "traceId", traceId,
                "reason", "NEVER_RECORDED",
                "message", "没有该追踪记录"));
    }
}

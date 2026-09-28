package com.github.xingjianluo.localrequesttracerjava.trace.query;

import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;

/**
 * 批量查询条件。时间范围按 trace 开始时间过滤（epoch 毫秒，闭区间）；
 * status 按整体成功/失败过滤；errorCategory 按失败最终归类过滤；
 * limit 为单页上限；cursor 为上一页返回的不透明游标（null 表示第一页）。
 */
public record TraceQuery(
        Long fromEpochMs,
        Long toEpochMs,
        SpanStatus status,
        ErrorCategory errorCategory,
        Integer limit,
        String cursor
) {
}

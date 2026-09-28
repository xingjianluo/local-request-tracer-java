package com.github.xingjianluo.localrequesttracerjava.trace.query;

import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;

/**
 * 最近追踪的批量查询条件。
 *
 * <p>时间窗按 trace 结束时间（endEpochMs）过滤；fromEpochMs 含边界，toEpochMs 含边界，
 * 为 0/负数表示该侧不限。status 为 null 表示成败都取；errorCategory 只在失败结果上有意义，
 * 非 null 时隐含只取失败。limit 为单页条数上限；cursor 为上一页返回的不透明游标。
 */
public record TraceQuery(
        long fromEpochMs,
        long toEpochMs,
        SpanStatus status,
        ErrorCategory errorCategory,
        int limit,
        String cursor
) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
}

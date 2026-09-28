package com.github.xingjianluo.localrequesttracerjava.trace.query;

import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSummary;

import java.util.List;

/**
 * 批量查询的一页结果。按 (startEpochMs 倒序, traceId 倒序) 稳定排序；
 * nextCursor 非 null 表示还有后续，hasMore 明示。
 */
public record TracePage(
        List<TraceSummary> items,
        String nextCursor,
        boolean hasMore
) {
}

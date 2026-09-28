package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 列表项视图：在完整快照之上附带一层归因汇总，供批量查询使用。不可变。
 */
public record TraceSummary(
        TraceSnapshot trace,
        TraceAttribution attribution
) {
}

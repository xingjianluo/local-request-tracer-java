package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 详情视图：完整快照 + 一层归因汇总。不可变。
 */
public record TraceDetail(
        TraceSnapshot trace,
        TraceAttribution attribution
) {
}

package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 一条追踪链中最费时间的那一环的归因摘要。
 *
 * @param spanId     该 span 的标识
 * @param name       span 名字
 * @param durationMs 该 span 的耗时（毫秒）
 */
public record SlowestSpan(String spanId, String name, long durationMs) {
}

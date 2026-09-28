package com.github.xingjianluo.localrequesttracerjava.trace.model;

import java.util.List;

/**
 * 一条追踪链的一层归因汇总：最终失败类别 + 最费时间的一环。
 *
 * <p>口径与入口无关：失败类别取 span 记录顺序上第一个失败 span 的归一化类别，
 * 同步入口、异步链路、异常被转译或包装后，只要根因相同结论就一致。
 */
public record TraceAttribution(ErrorCategory errorCategory, SlowestSpan slowestSpan) {

    /**
     * 从不可变 span 列表计算整链归因；只做纯计算，不修改入参。
     *
     * <p>最终失败类别：span 列表按结束先后排列，取第一个 FAILED span 的类别——
     * 它代表链路上最早暴露失败的一环；span 上的类别已经是异常类型归一化的结论，
     * 与同步/异步入口、包装转译无关，因此整链类别在不同路径下保持一致。
     *
     * <p>最慢一环：优先在<b>子 span</b>（parentSpanId 非空）中取 durationMs 最大者，
     * 因为根 span 包住整条链路、耗时天然最大但不指向具体卡点；
     * 若整条链路只有根 span（没有子操作），回退到根 span，保证归因总有值。
     * 并列时取列表中更早的 span，保证结论确定。
     */
    public static TraceAttribution of(List<SpanData> spans) {
        ErrorCategory failedCategory = null;
        SpanData slowestChild = null;
        SpanData root = null;
        for (SpanData span : spans) {
            if (failedCategory == null
                    && span.status() == SpanStatus.FAILED
                    && span.errorCategory() != null) {
                failedCategory = span.errorCategory();
            }
            if (span.parentSpanId() == null) {
                if (root == null) {
                    root = span;
                }
            } else if (slowestChild == null || span.durationMs() > slowestChild.durationMs()) {
                slowestChild = span;
            }
        }
        SpanData slowest = slowestChild != null ? slowestChild : root;
        SlowestSpan slowestSpan = slowest == null
                ? null
                : new SlowestSpan(slowest.spanId(), slowest.name(), slowest.durationMs());
        return new TraceAttribution(failedCategory, slowestSpan);
    }
}

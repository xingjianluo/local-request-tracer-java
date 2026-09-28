package com.github.xingjianluo.localrequesttracerjava.trace.model;

import java.util.Comparator;
import java.util.List;

/**
 * 从 {@link TraceSnapshot} 派生一层归因汇总的纯函数集合。
 *
 * <p>口径只依赖定型快照的内容，不依赖入口（同步/异步）或异常被转译包装的路径：
 * <ul>
 *   <li>整体结论：快照 status（任一 span 失败即 FAILED）；</li>
 *   <li>最终失败类别：在所有失败 span 的类别中按
 *       {@link ErrorCategory#priority()} 取最高者，
 *       并列时取最早开始、spanId 最小者，保证可复现；
 *       失败但类别缺失按 {@link ErrorCategory#UNKNOWN} 兜底；</li>
 *   <li>最费时间环节：durationMs 最大的 span，并列时取最早开始、spanId 最小者。</li>
 * </ul>
 */
public final class TraceAttributions {

    private TraceAttributions() {
    }

    public static TraceAttribution of(TraceSnapshot snapshot) {
        List<SpanData> spans = snapshot.spans();

        ErrorCategory category = null;
        if (snapshot.status() == SpanStatus.FAILED) {
            category = spans.stream()
                    .filter(s -> s.status() == SpanStatus.FAILED)
                    .max(FAILURE_CATEGORY_ORDER)
                    .map(s -> s.errorCategory() != null ? s.errorCategory() : ErrorCategory.UNKNOWN)
                    .orElse(ErrorCategory.UNKNOWN);
        }

        SpanData slowest = spans.stream().max(SLOWEST_ORDER).orElse(null);
        return new TraceAttribution(
                snapshot.status(),
                category,
                slowest == null ? null : slowest.name(),
                slowest == null ? 0L : slowest.durationMs()
        );
    }

    public static TraceSummary summarize(TraceSnapshot snapshot) {
        return new TraceSummary(snapshot, of(snapshot));
    }

    public static TraceDetail detail(TraceSnapshot snapshot) {
        return new TraceDetail(snapshot, of(snapshot));
    }

    private static final Comparator<SpanData> SLOWEST_ORDER =
            Comparator.comparingLong(SpanData::durationMs)
                    .thenComparing(Comparator.comparingLong(SpanData::startEpochMs).reversed())
                    .thenComparing(s -> s.spanId() == null ? "" : s.spanId(),
                            Comparator.reverseOrder());

    private static final Comparator<SpanData> FAILURE_CATEGORY_ORDER =
            Comparator.comparing((SpanData s) ->
                            s.errorCategory() != null ? s.errorCategory() : ErrorCategory.UNKNOWN,
                    Comparator.comparingInt(ErrorCategory::priority))
                    .thenComparing(Comparator.comparingLong(SpanData::startEpochMs).reversed())
                    .thenComparing(s -> s.spanId() == null ? "" : s.spanId(),
                            Comparator.reverseOrder());
}

package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.model.LatencyClass;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanData;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceAttribution;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceAttributions;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 一层归因汇总：
 * 成功不带失败类别；失败取优先级最高的类别；最耗时环节按耗时+稳定兜底选取；
 * 结论只取决于快照内容，与 span 排列顺序（同步/异步路径）无关。
 */
class TraceAttributionTest {

    private static SpanData span(String id, String name, long start, long duration,
                                SpanStatus status, ErrorCategory category) {
        return new SpanData(id, null, name, "INTERNAL", start, duration,
                "t", status, category,
                category == null ? null : category.safeMessage(), LatencyClass.NORMAL);
    }

    @Test
    void successHasNoErrorCategoryAndReportsSlowest() {
        TraceSnapshot snapshot = new TraceSnapshot("t", 0L, 100L, 100L, SpanStatus.SUCCESS, List.of(
                span("a", "fast", 0L, 10L, SpanStatus.SUCCESS, null),
                span("b", "slow", 10L, 80L, SpanStatus.SUCCESS, null)
        ));

        TraceAttribution attribution = TraceAttributions.of(snapshot);

        assertEquals(SpanStatus.SUCCESS, attribution.status());
        assertNull(attribution.errorCategory(), "成功链路不应有失败类别");
        assertEquals("slow", attribution.slowestSpanName());
        assertEquals(80L, attribution.slowestSpanDurationMs());
    }

    @Test
    void failedTraceFinalCategoryIsHighestPriorityRegardlessOfOrder() {
        // 先出现 VALIDATION，后出现 TIMEOUT：TIMEOUT 优先级更高，必须成为最终归类
        TraceSnapshot s1 = new TraceSnapshot("t", 0L, 100L, 100L, SpanStatus.FAILED, List.of(
                span("a", "validation", 0L, 5L, SpanStatus.FAILED, ErrorCategory.VALIDATION_ERROR),
                span("b", "timeout", 5L, 10L, SpanStatus.FAILED, ErrorCategory.TIMEOUT)
        ));
        // 颠倒 span 顺序，结论必须一致（模拟同步直入 vs 绕异步后聚合的不同排列）
        TraceSnapshot s2 = new TraceSnapshot("t", 0L, 100L, 100L, SpanStatus.FAILED, List.of(
                span("b", "timeout", 5L, 10L, SpanStatus.FAILED, ErrorCategory.TIMEOUT),
                span("a", "validation", 0L, 5L, SpanStatus.FAILED, ErrorCategory.VALIDATION_ERROR)
        ));

        assertEquals(ErrorCategory.TIMEOUT, TraceAttributions.of(s1).errorCategory());
        assertEquals(ErrorCategory.TIMEOUT, TraceAttributions.of(s2).errorCategory(),
                "span 排列不同（不同入口路径）不得改变最终归类");
    }

    @Test
    void slowestTieBreaksDeterministicallyByStartThenSpanId() {
        // 耗时相同：最早开始优先；再相同：spanId 最小优先
        TraceSnapshot snapshot = new TraceSnapshot("t", 0L, 100L, 100L, SpanStatus.SUCCESS, List.of(
                span("z", "later", 10L, 50L, SpanStatus.SUCCESS, null),
                span("b", "earlier-b", 5L, 50L, SpanStatus.SUCCESS, null),
                span("a", "earlier-a", 5L, 50L, SpanStatus.SUCCESS, null)
        ));

        TraceAttribution attribution = TraceAttributions.of(snapshot);
        assertEquals("earlier-a", attribution.slowestSpanName(), "并列耗时应取最早开始且 spanId 最小者");
        assertEquals(50L, attribution.slowestSpanDurationMs());
    }

    @Test
    void failedWithoutCategoryFallsBackToUnknown() {
        TraceSnapshot snapshot = new TraceSnapshot("t", 0L, 100L, 100L, SpanStatus.FAILED, List.of(
                span("a", "mystery", 0L, 5L, SpanStatus.FAILED, null)
        ));
        assertEquals(ErrorCategory.UNKNOWN, TraceAttributions.of(snapshot).errorCategory());
    }

    @Test
    void emptyTraceHasNoSlowestSpan() {
        TraceSnapshot snapshot = new TraceSnapshot("t", 0L, 0L, 0L, SpanStatus.SUCCESS, List.of());
        TraceAttribution attribution = TraceAttributions.of(snapshot);
        assertNull(attribution.slowestSpanName());
        assertEquals(0L, attribution.slowestSpanDurationMs());
    }
}

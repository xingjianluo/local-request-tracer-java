package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 错误归一化：类别可区分、判定与入口无关、对外文案不泄露内部细节。
 */
class ErrorClassifierTest {

    private static final Logger log = LoggerFactory.getLogger(ErrorClassifierTest.class);

    private final ErrorClassifier classifier = new ErrorClassifier();

    private void assertCategory(Throwable t, ErrorCategory expected, String basis) {
        ErrorCategory actual = classifier.classify(t);
        log.info("classify basis={} exception={} -> category={} (expected={})",
                basis, t.getClass().getSimpleName(), actual, expected);
        assertEquals(expected, actual, basis);
    }

    @Test
    void distinguishesStableCategories() {
        assertCategory(new IllegalArgumentException("bad arg"), ErrorCategory.VALIDATION_ERROR, "参数异常→400类");
        assertCategory(new IllegalStateException("bad state"), ErrorCategory.CONFLICT, "状态冲突→409类");
        assertCategory(new TimeoutException("too slow"), ErrorCategory.TIMEOUT, "超时→504类");
        assertCategory(new SocketTimeoutException("read timed out"), ErrorCategory.TIMEOUT, "socket超时→504类");
        assertCategory(new ConnectException("refused"), ErrorCategory.UPSTREAM_FAILURE, "连接拒绝→502类");
        assertCategory(new IOException("broken pipe"), ErrorCategory.UPSTREAM_FAILURE, "IO失败→502类");
        assertCategory(new RejectedExecutionException("pool full"), ErrorCategory.RATE_LIMITED, "线程池拒绝→429类");
        assertCategory(new RuntimeException("boom"), ErrorCategory.INTERNAL_ERROR, "未识别运行时异常→500类");
        assertCategory(new Exception("checked"), ErrorCategory.UNKNOWN, "未识别受检异常→UNKNOWN");
    }

    @Test
    void unwrapsAsyncWrappersToSameCategory() {
        // 同一失败经不同异步包装后，结论必须一致
        ConnectException root = new ConnectException("refused");
        assertCategory(root, ErrorCategory.UPSTREAM_FAILURE, "原始异常");
        assertCategory(new CompletionException(root), ErrorCategory.UPSTREAM_FAILURE, "CompletionException包装");
        assertCategory(new ExecutionException(root), ErrorCategory.UPSTREAM_FAILURE, "ExecutionException包装");
        assertCategory(new CompletionException(new ExecutionException(root)),
                ErrorCategory.UPSTREAM_FAILURE, "双层包装");
    }

    @Test
    void genericWrappersAlsoResolveToRootCause() {
        // 非异步专用包装（普通 RuntimeException / Exception 转译）也要沿 cause 链找到根因
        ConnectException root = new ConnectException("refused");
        assertCategory(new RuntimeException(root), ErrorCategory.UPSTREAM_FAILURE,
                "RuntimeException 普通包装");
        assertCategory(new Exception(new RuntimeException(root)), ErrorCategory.UPSTREAM_FAILURE,
                "受检异常双层包装");
        assertCategory(new RuntimeException(new java.util.concurrent.TimeoutException("slow")),
                ErrorCategory.TIMEOUT, "包装内超时优先识别");
        // 无具体根因时维持兜底，不因 cause 链改变结论
        assertCategory(new RuntimeException(new RuntimeException("boom")),
                ErrorCategory.INTERNAL_ERROR, "链上无具体类别→兜底 INTERNAL_ERROR");
    }

    @Test
    void deeperSpecificRootCauseWinsOverSpecificMiddleLayer() {
        // 中间层本身也对得上某个具体类别，但更深处有更明确的根因：以更深处为准。
        // 这是本次口径细化的关键场景：同一根因直接抛 TIMEOUT，
        // 若被 IllegalStateException（CONFLICT）转译后归成 CONFLICT，
        // 按类别筛选就会把同一条根因拆成两类。
        assertCategory(new IllegalStateException(new TimeoutException("slow")),
                ErrorCategory.TIMEOUT, "外层 CONFLICT 包装深处 TIMEOUT→TIMEOUT");
        assertCategory(new IllegalArgumentException(new SocketTimeoutException("read timed out")),
                ErrorCategory.TIMEOUT, "外层 VALIDATION 包装深处 socket 超时→TIMEOUT");
        assertCategory(new IllegalStateException(new ConnectException("refused")),
                ErrorCategory.UPSTREAM_FAILURE, "外层 CONFLICT 包装深处连接失败→UPSTREAM_FAILURE");
        assertCategory(new RejectedExecutionException(new TimeoutException("slow")),
                ErrorCategory.TIMEOUT, "外层 RATE_LIMITED 包装深处超时→TIMEOUT");
        // 直接抛出时结论必须与被包装/转译时一致，统计不被路径拆分
        assertCategory(new TimeoutException("slow"), ErrorCategory.TIMEOUT, "直接抛超时→TIMEOUT（对照）");
        assertCategory(new ConnectException("refused"),
                ErrorCategory.UPSTREAM_FAILURE, "直接抛连接失败→UPSTREAM_FAILURE（对照）");
    }

    @Test
    void sameRootCauseAcrossSyncAsyncAndTranslationPathsClassifiesSame() {
        // 同一条"下游超时"根因，模拟 同步直接抛 / 异步透明包装 / 中间转译 三种路径
        TimeoutException root = new TimeoutException("downstream timed out");
        assertCategory(root, ErrorCategory.TIMEOUT, "同步直接抛");
        assertCategory(new CompletionException(root), ErrorCategory.TIMEOUT, "异步 CompletionException 包装");
        assertCategory(new ExecutionException(root), ErrorCategory.TIMEOUT, "异步 ExecutionException 包装");
        assertCategory(new CompletionException(new ExecutionException(root)),
                ErrorCategory.TIMEOUT, "双层异步包装");
        assertCategory(new IllegalStateException(root), ErrorCategory.TIMEOUT,
                "中途转译为 IllegalStateException（外层本身具体→CONFLICT，仍以深处 TIMEOUT 为准）");
        assertCategory(new RuntimeException(new IllegalStateException(root)),
                ErrorCategory.TIMEOUT, "普通包装 + 具体中间层 + 深处超时");
        assertCategory(new CompletionException(new IllegalStateException(new TimeoutException("x"))),
                ErrorCategory.TIMEOUT, "异步包装后再被具体异常转译");
    }

    @Test
    void noSpecificCauseGivesStableOutermostFallback() {
        // 整条链上确实没有更具体的根因：维持最外层兜底结论
        assertCategory(new RuntimeException(new RuntimeException("boom")),
                ErrorCategory.INTERNAL_ERROR, "链上无具体类别→兜底 INTERNAL_ERROR");
        // 外层是未识别受检异常（UNKNOWN），内层是普通 RuntimeException（INTERNAL_ERROR）：
        // 两层都属兜底，取最外层结论 UNKNOWN，结论确定不随链长变化
        assertCategory(new Exception(new RuntimeException("boom")),
                ErrorCategory.UNKNOWN, "全兜底链取最外层类别 UNKNOWN");
    }

    @Test
    void cyclicCauseChainDoesNotHang() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        // cause 成环也必须正常返回，不能死循环
        assertEquals(ErrorCategory.INTERNAL_ERROR, classifier.classify(a),
                "cause 成环时安全终止并给兜底类别");

        // 环中带具体根因也要安全终止并取到具体类别：c -> d(具体) -> c 成环
        RuntimeException c = new RuntimeException("c");
        IllegalStateException d = new IllegalStateException("state", c);
        c.initCause(d);
        assertEquals(ErrorCategory.CONFLICT, classifier.classify(c),
                "成环但环上有具体类别时安全终止并给该类别");
    }

    @Test
    void safeMessageNeverLeaksInternals() {
        RuntimeException sensitive = new RuntimeException("db password=secret at com.internal.Foo.bar(Foo.java:42)");
        String message = classifier.safeMessage(sensitive);
        log.info("safeMessage category={} message={}", classifier.classify(sensitive), message);
        assertFalse(message.contains("password"), "安全文案不得包含异常消息");
        assertFalse(message.contains("com.internal"), "安全文案不得包含内部类名");
        assertEquals(ErrorCategory.INTERNAL_ERROR.safeMessage(), message);
    }
}

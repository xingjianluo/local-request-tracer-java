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
    void cyclicCauseChainDoesNotHang() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        // cause 成环也必须正常返回，不能死循环
        assertEquals(ErrorCategory.INTERNAL_ERROR, classifier.classify(a),
                "cause 成环时安全终止并给兜底类别");
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

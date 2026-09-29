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
    void deeperMoreSpecificRootCauseWinsOverConcreteOuterCategory() {
        // 外层本身也对得上某个具体类别（CONFLICT），但更深处是更具体的超时根因：
        // 必须以更深处更明确的根因为准
        assertCategory(new IllegalStateException(new TimeoutException("slow")),
                ErrorCategory.TIMEOUT, "外层 CONFLICT 但深处 TIMEOUT → TIMEOUT");
        // 参数异常（VALIDATION_ERROR）包着 socket 超时，同样以深处根因为准
        assertCategory(new IllegalArgumentException(new SocketTimeoutException("read timed out")),
                ErrorCategory.TIMEOUT, "外层 VALIDATION 但深处 socket 超时 → TIMEOUT");
        // 外层 CONFLICT、深处连接失败：UPSTREAM_FAILURE 比 CONFLICT 更接近根因
        assertCategory(new IllegalStateException(new ConnectException("refused")),
                ErrorCategory.UPSTREAM_FAILURE, "外层 CONFLICT 但深处连接失败 → UPSTREAM_FAILURE");
        // 外层 CONFLICT、深处普通 IO 失败
        assertCategory(new IllegalStateException(new IOException("broken pipe")),
                ErrorCategory.UPSTREAM_FAILURE, "外层 CONFLICT 但深处 IO 失败 → UPSTREAM_FAILURE");
        // 异步透明包装再被具体异常转译一次，结论仍由最深处根因决定
        assertCategory(new IllegalArgumentException(
                        new CompletionException(new SocketTimeoutException("read timed out"))),
                ErrorCategory.TIMEOUT, "转译 + 异步双层包装后仍以深处 TIMEOUT 为准");
        // 超时压过上游 IO：IOException 内层再包超时，仍归 TIMEOUT
        assertCategory(new IOException(new SocketTimeoutException("read timed out")),
                ErrorCategory.TIMEOUT, "IO 包装超时 → 更具体的 TIMEOUT");
    }

    @Test
    void sameRootCauseWrappedOrTranslatedKeepsOneCategory() {
        // 同一条超时根因，直接抛 / 异步包装 / 被具体异常转译，类别必须完全一致
        Throwable root = new SocketTimeoutException("read timed out");
        assertCategory(root, ErrorCategory.TIMEOUT, "同步直接抛");
        assertCategory(new CompletionException(root), ErrorCategory.TIMEOUT, "异步 CompletionException");
        assertCategory(new ExecutionException(root), ErrorCategory.TIMEOUT, "异步 ExecutionException");
        assertCategory(new IllegalStateException(root), ErrorCategory.TIMEOUT,
                "被 CONFLICT 类异常转译包装");
        assertCategory(new IllegalArgumentException(new RuntimeException(root)),
                ErrorCategory.TIMEOUT, "被多层异常转译包装");

        // 同一条连接失败根因走同样几条路径也一致（含外层本身命中 CONFLICT 的情形）
        Throwable refused = new ConnectException("refused");
        for (Throwable t : java.util.List.of(
                refused,
                new CompletionException(refused),
                new IllegalStateException(refused),
                new IllegalArgumentException(new RuntimeException(refused)))) {
            assertEquals(ErrorCategory.UPSTREAM_FAILURE, classifier.classify(t),
                    "连接失败根因在任何包装/转译路径下归类一致: " + t.getClass().getSimpleName());
        }
    }

    @Test
    void noMoreSpecificCauseFallsBackSafelyAndCyclesAcrossConcreteCategoriesTerminate() {
        // 外层具体、链上没有更具体的根因：维持外层类别，不被兜底内层改写
        assertCategory(new IllegalStateException(new RuntimeException("boom")),
                ErrorCategory.CONFLICT, "外层 CONFLICT、内层仅兜底 → 维持 CONFLICT");
        assertCategory(new IllegalArgumentException(new Exception("checked")),
                ErrorCategory.VALIDATION_ERROR, "外层 VALIDATION、内层受检未知 → 维持 VALIDATION");
        // 链上只有兜底类别：INTERNAL_ERROR 优先于 UNKNOWN，链上深处兜底不改变结论
        assertCategory(new RuntimeException(new Exception("checked")),
                ErrorCategory.INTERNAL_ERROR, "运行时兜底不被 UNKNOWN 内层改写");

        // 具体类别之间互相引用成环：必须安全终止，不卡死、不报错
        IllegalStateException a = new IllegalStateException("a");
        IllegalArgumentException b = new IllegalArgumentException("b");
        a.initCause(b);
        b.initCause(a);
        ErrorCategory cyclic = classifier.classify(a);
        assertEquals(ErrorCategory.VALIDATION_ERROR, cyclic,
                "具体类别成环时安全终止，并按同优先级取更深一层");
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

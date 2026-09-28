package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 把原始异常归一化为稳定错误类别。
 *
 * <p>口径：
 * <ul>
 *   <li>判定只依赖异常类型（含包装链与 cause 链），与入口、消息内容无关，
 *       同一类失败在任何入口（同步/异步）、任何包装或转译层数下结论一致；</li>
 *   <li>沿 cause 链做有限深度（防环）遍历，对每层给出“类别 + 具体度”，
 *       取具体度最高者；并列时取更靠近根因的一层，
 *       从而不被外层笼统的 RuntimeException / 包装异常带偏；</li>
 *   <li>对外文案只取 {@link ErrorCategory#safeMessage()}，
 *       绝不携带异常消息、类名或堆栈。</li>
 * </ul>
 */
public final class ErrorClassifier {

    /** cause 链最大遍历深度，防止病态自引用导致无限循环。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    public ErrorCategory classify(Throwable t) {
        if (t == null) {
            return ErrorCategory.UNKNOWN;
        }
        ErrorCategory winner = ErrorCategory.UNKNOWN;
        int winnerScore = 0;
        Throwable current = t;
        java.util.Set<Throwable> seen = new java.util.HashSet<>();
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            ErrorCategory category = doClassify(current);
            int score = specificity(current, category);
            // 具体度更高者胜出；并列时继续向根因深入（根因优先）
            if (score > winnerScore) {
                winnerScore = score;
                winner = category;
            }
            current = current.getCause();
        }
        return winner;
    }

    /** 对外安全文案：只来自类别定义，绝不包含异常消息或堆栈。 */
    public String safeMessage(Throwable t) {
        return classify(t).safeMessage();
    }

    /**
     * 单层异常的类型判定与具体度。越能说明真实失败原因的类型具体度越高；
     * 笼统包装层（RuntimeException 等）具体度低，不会盖过根因。
     */
    private int specificity(Throwable t, ErrorCategory category) {
        // 超时：最具体
        if (t instanceof TimeoutException
                || t instanceof SocketTimeoutException
                || t instanceof HttpTimeoutException
                || t instanceof HttpConnectTimeoutException) {
            return 100;
        }
        // 明确的参数/绑定问题
        if (t instanceof IllegalArgumentException
                || t instanceof BindException
                || t instanceof MethodArgumentTypeMismatchException
                || t instanceof MissingServletRequestParameterException
                || t instanceof HttpMessageNotReadableException) {
            return 90;
        }
        // 明确的下游连接失败
        if (t instanceof ConnectException) {
            return 85;
        }
        // 显式 HTTP 语义
        if (t instanceof ResponseStatusException || t.getClass().isAnnotationPresent(ResponseStatus.class)) {
            return 80;
        }
        if ("AccessDeniedException".equals(t.getClass().getSimpleName())) {
            return 80;
        }
        if (t instanceof NoResourceFoundException) {
            return 80;
        }
        if (t instanceof RejectedExecutionException) {
            return 80;
        }
        // 其他 IO：连接类以外，具体度中
        if (t instanceof IOException) {
            return 70;
        }
        // 状态冲突：IllegalStateException 较笼统，避免盖过被包装的具体根因
        if (t instanceof IllegalStateException) {
            return 60;
        }
        // 已知的异步/包装层：基本不具备独立归因价值
        if (t instanceof CompletionException || t instanceof ExecutionException) {
            return 5;
        }
        // 未识别 RuntimeException 与 UNKNOWN：最低档
        return category == ErrorCategory.UNKNOWN ? 1 : 10;
    }

    private ErrorCategory doClassify(Throwable t) {
        // 超时优先于上游失败：超时是更具体的归因
        if (t instanceof TimeoutException
                || t instanceof SocketTimeoutException
                || t instanceof HttpTimeoutException
                || t instanceof HttpConnectTimeoutException) {
            return ErrorCategory.TIMEOUT;
        }
        // 参数与绑定问题 → 400
        if (t instanceof IllegalArgumentException
                || t instanceof BindException
                || t instanceof MethodArgumentTypeMismatchException
                || t instanceof MissingServletRequestParameterException
                || t instanceof HttpMessageNotReadableException) {
            return ErrorCategory.VALIDATION_ERROR;
        }
        // 认证 / 授权：按类名匹配，避免对 spring-security 的硬依赖
        if ("AccessDeniedException".equals(t.getClass().getSimpleName())) {
            return ErrorCategory.FORBIDDEN;
        }
        // 资源不存在
        if (t instanceof NoResourceFoundException) {
            return ErrorCategory.NOT_FOUND;
        }
        // 并发与容量
        if (t instanceof IllegalStateException) {
            return ErrorCategory.CONFLICT;
        }
        if (t instanceof RejectedExecutionException) {
            return ErrorCategory.RATE_LIMITED;
        }
        // 下游调用失败（连接类 IO 优先于普通 IO，但同为 UPSTREAM_FAILURE）
        if (t instanceof ConnectException) {
            return ErrorCategory.UPSTREAM_FAILURE;
        }
        if (t instanceof IOException) {
            return ErrorCategory.UPSTREAM_FAILURE;
        }
        // 显式声明 HTTP 语义的异常
        if (t instanceof ResponseStatusException rse) {
            return fromStatusCode(rse.getStatusCode().value());
        }
        ResponseStatus rs = t.getClass().getAnnotation(ResponseStatus.class);
        if (rs != null) {
            return fromStatusCode(rs.value().value());
        }
        // 兜底：受检/未知异常不暴露细节
        if (t instanceof RuntimeException) {
            return ErrorCategory.INTERNAL_ERROR;
        }
        return ErrorCategory.UNKNOWN;
    }

    private ErrorCategory fromStatusCode(int status) {
        return switch (status) {
            case 400 -> ErrorCategory.VALIDATION_ERROR;
            case 401 -> ErrorCategory.UNAUTHENTICATED;
            case 403 -> ErrorCategory.FORBIDDEN;
            case 404 -> ErrorCategory.NOT_FOUND;
            case 409 -> ErrorCategory.CONFLICT;
            case 429 -> ErrorCategory.RATE_LIMITED;
            case 502 -> ErrorCategory.UPSTREAM_FAILURE;
            case 503 -> ErrorCategory.UPSTREAM_FAILURE;
            case 504 -> ErrorCategory.TIMEOUT;
            default -> status >= 500 ? ErrorCategory.INTERNAL_ERROR : ErrorCategory.UNKNOWN;
        };
    }
}

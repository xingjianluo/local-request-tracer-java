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
 *       同一类失败在任何入口下结论一致；</li>
 *   <li>对外文案只取 {@link ErrorCategory#safeMessage()}，
 *       绝不携带异常消息、类名或堆栈。</li>
 * </ul>
 */
public final class ErrorClassifier {

    public ErrorCategory classify(Throwable t) {
        if (t == null) {
            return ErrorCategory.UNKNOWN;
        }
        // 第一步：剥掉异步/反应式透明包装层
        Throwable root = unwrap(t);
        ErrorCategory direct = doClassify(root);
        // 外层本身已能确定具体类别时直接采用；
        // 只落到兜底（INTERNAL_ERROR/UNKNOWN）时，沿 cause 链找更具体的根因，
        // 保证同一种失败被任意 RuntimeException/受检异常转译包装后归类仍一致。
        if (direct != ErrorCategory.INTERNAL_ERROR && direct != ErrorCategory.UNKNOWN) {
            return direct;
        }
        ErrorCategory deeper = classifyByCause(root);
        return deeper != null ? deeper : direct;
    }

    /** 沿 cause 链寻找第一个能确定具体类别的原因；用身份集合防止 cause 自引用成环。 */
    private ErrorCategory classifyByCause(Throwable t) {
        java.util.Set<Throwable> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        visited.add(t);
        Throwable current = t.getCause();
        while (current != null && visited.add(current)) {
            ErrorCategory category = doClassify(unwrap(current));
            if (category != ErrorCategory.INTERNAL_ERROR && category != ErrorCategory.UNKNOWN) {
                return category;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 对外安全文案：只来自类别定义，绝不包含异常消息或堆栈。 */
    public String safeMessage(Throwable t) {
        return classify(t).safeMessage();
    }

    /** 剥掉异步/反应式包装层，拿到真实失败原因。 */
    private Throwable unwrap(Throwable t) {
        Throwable current = t;
        while (current != null
                && (current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
        // 下游调用失败（连接类 IO）
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

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
        // 遍历整条 cause 链（CompletionException/ExecutionException 等异步透明包装
        // 本身只落到兜底类别，无需特判剥层），在链上选出"最具体的根因类别"：
        //   1) 先按类别具体度优先级 specificity 取高者——更深处更明确的根因
        //      可以压过外层本身已对上的类别（如 IllegalStateException(TimeoutException)
        //      仍归 TIMEOUT，而非 CONFLICT）；
        //   2) 优先级相同时取链上更深者——同一组异常无论从同步入口、绕异步、
        //      还是被任意 RuntimeException/受检异常转译包装，结论都一致。
        // 身份集合防止 cause 自引用/互相引用成环导致死循环。
        java.util.Set<Throwable> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        ErrorCategory best = ErrorCategory.UNKNOWN;
        Throwable current = t;
        while (current != null && visited.add(current)) {
            ErrorCategory category = doClassify(current);
            if (replaceBySpecificity(category, best, current, t)) {
                best = category;
            }
            current = current.getCause();
        }
        return best;
    }

    /**
     * 是否用链上更深一层的类别 {@code candidate} 替换当前结论 {@code incumbent}：
     * 具体度优先级更高则替换；优先级相同且候选位于更深层（不是最外层）时也替换。
     */
    private boolean replaceBySpecificity(ErrorCategory candidate, ErrorCategory incumbent,
                                         Throwable candidateNode, Throwable outermost) {
        int candidateRank = specificity(candidate);
        int incumbentRank = specificity(incumbent);
        if (candidateRank != incumbentRank) {
            return candidateRank > incumbentRank;
        }
        return candidateRank > 0 && candidateNode != outermost;
    }

    /**
     * 类别具体度优先级：越大越"像根因"。
     * 兜底类别（UNKNOWN/INTERNAL_ERROR）最低；超时是比上游 IO 失败更具体的归因，
     * 因此无论处在链上哪一层都压过 UPSTREAM_FAILURE。
     */
    private int specificity(ErrorCategory category) {
        return switch (category) {
            case UNKNOWN -> 0;
            case INTERNAL_ERROR -> 1;
            case TIMEOUT -> 5;
            case UPSTREAM_FAILURE -> 4;
            default -> 2;
        };
    }

    /** 对外安全文案：只来自类别定义，绝不包含异常消息或堆栈。 */
    public String safeMessage(Throwable t) {
        return classify(t).safeMessage();
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

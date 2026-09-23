package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.context.TraceContext;
import com.github.xingjianluo.localrequesttracerjava.trace.model.ErrorCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口。
 *
 * <p>口径：
 * <ul>
 *   <li>异常先经 {@link ErrorClassifier} 归一化，同一类失败在任何入口下
 *       返回相同的类别、状态码与安全文案；</li>
 *   <li>响应体只含 category / safeMessage / traceId，
 *       不暴露异常类名、消息或堆栈；</li>
 *   <li>原始异常只进服务端日志（带 traceId），便于内部排查。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;
    private final ErrorClassifier classifier;

    public GlobalExceptionHandler(Tracer tracer, ErrorClassifier classifier) {
        this.tracer = tracer;
        this.classifier = classifier;
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiError> handle(Throwable t) {
        ErrorCategory category = classifier.classify(t);
        String traceId = TraceContext.currentTraceId();
        tracer.markCurrentFailed(t);
        // 原始异常只进服务端日志，不进响应体
        log.warn("request failed traceId={} category={} reason={}",
                traceId, category.name(), t.toString());
        ApiError body = new ApiError(category.name(), category.safeMessage(), traceId);
        return ResponseEntity.status(category.httpStatus()).body(body);
    }
}

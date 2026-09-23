package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import com.github.xingjianluo.localrequesttracerjava.trace.TraceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口：只返回归一化类别与 traceId，不暴露堆栈或内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handle(Throwable error) {
        ErrorCategory category = ErrorClassifier.classify(error);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", category.name());
        body.put("traceId", TraceContext.currentTraceId());
        HttpStatus status = switch (category) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(body);
    }
}

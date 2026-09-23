package com.github.xingjianluo.localrequesttracerjava.trace;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 将原始异常归一化为稳定的错误类别。
 * 规则只看异常类型（含 cause 链），与入口无关，保证同一类失败结论一致。
 */
public final class ErrorClassifier {

    private ErrorClassifier() {
    }

    public static ErrorCategory classify(Throwable error) {
        if (error == null) {
            return ErrorCategory.NONE;
        }
        // 从根因向表层查找第一个可识别的类别：
        // 包装异常（如 IllegalStateException 包一层）不得掩盖真正的失败原因，
        // 保证同一类失败在不同入口下结论一致。
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable t = error; t != null; t = t.getCause()) {
            chain.add(t);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            ErrorCategory c = classifyOne(chain.get(i));
            if (c != null) {
                return c;
            }
        }
        return ErrorCategory.UNKNOWN;
    }

    private static ErrorCategory classifyOne(Throwable t) {
        if (t instanceof TimeoutException) {
            return ErrorCategory.TIMEOUT;
        }
        if (t instanceof RejectedExecutionException || t instanceof CancellationException) {
            return ErrorCategory.CONCURRENCY;
        }
        if (t instanceof IllegalArgumentException) {
            return ErrorCategory.VALIDATION;
        }
        if (t instanceof IllegalStateException) {
            return ErrorCategory.RESOURCE;
        }
        if (t instanceof IOException) {
            return ErrorCategory.IO;
        }
        return null;
    }
}

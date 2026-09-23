package com.github.xingjianluo.localrequesttracerjava.trace;

/**
 * 归一化后的错误类别。对外只暴露类别，不暴露内部堆栈。
 */
public enum ErrorCategory {
    NONE,
    TIMEOUT,
    IO,
    VALIDATION,
    CONCURRENCY,
    RESOURCE,
    UNKNOWN
}

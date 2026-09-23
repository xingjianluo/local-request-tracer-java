package com.github.xingjianluo.localrequesttracerjava.trace.model;

/** 延迟归因：NORMAL 正常；SLOW 超过慢操作阈值。 */
public enum LatencyClass {
    NORMAL,
    SLOW
}

package com.github.xingjianluo.localrequesttracerjava.trace.model;

/**
 * 按标识查询的明确结论：
 * FOUND 命中；EXPIRED 曾记录但已按 TTL 过期回收；
 * EVICTED 曾记录但因容量上限被 FIFO 淘汰；UNKNOWN 从未记录。
 */
public enum FindStatus {
    FOUND,
    EXPIRED,
    EVICTED,
    UNKNOWN
}

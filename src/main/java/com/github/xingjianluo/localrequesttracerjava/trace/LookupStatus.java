package com.github.xingjianluo.localrequesttracerjava.trace;

/**
 * 按 traceId 查询的四种确定结论，调用方据此区分"没有"的具体原因。
 */
public enum LookupStatus {
    /** 命中，记录完整在库。 */
    FOUND,
    /** 曾经记录过，但因超过保留时长被过期回收。 */
    EXPIRED,
    /** 曾经记录过，但因容量上限被 FIFO 顶掉。 */
    EVICTED,
    /** 从未记录过该标识。 */
    NEVER_RECORDED
}

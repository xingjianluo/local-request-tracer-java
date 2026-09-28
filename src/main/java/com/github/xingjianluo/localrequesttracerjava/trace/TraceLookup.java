package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;

/**
 * 按标识查询的结论对象：状态 + 命中时的完整不可变快照。
 */
public record TraceLookup(LookupStatus status, TraceSnapshot snapshot) {

    public static TraceLookup found(TraceSnapshot snapshot) {
        return new TraceLookup(LookupStatus.FOUND, snapshot);
    }

    public static TraceLookup gone(LookupStatus status) {
        return new TraceLookup(status, null);
    }

    public static TraceLookup neverRecorded() {
        return new TraceLookup(LookupStatus.NEVER_RECORDED, null);
    }

    public boolean isFound() {
        return status == LookupStatus.FOUND;
    }
}

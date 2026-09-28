package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;

import java.util.Optional;

/**
 * 本地有界追踪存储。实现必须线程安全，查询只返回不可变快照。
 *
 * <p>回收有两个独立来源：超过保留时长（过期）与容量上限（FIFO 顶掉）。
 * 无论哪种回收，按标识查询都要能与"从未记录过"区分开。
 */
public interface TraceStore {

    void save(TraceSnapshot snapshot);

    /**
     * 按标识查询的完整结论：命中 / 已过期 / 被容量顶掉 / 从未记录。
     * 命中时返回的快照不可变，调用方不可能读到半截记录。
     */
    TraceLookup lookup(String traceId);

    /** 按条件批量查最近追踪，结果按结束时间倒序、稳定次序返回一页。 */
    TracePage query(TraceQuery query);

    /** 主动触发一次过期回收；返回被清掉的条数。 */
    int purgeExpired();

    default Optional<TraceSnapshot> find(String traceId) {
        TraceLookup lookup = lookup(traceId);
        return lookup.isFound() ? Optional.of(lookup.snapshot()) : Optional.empty();
    }

    int size();

    int capacity();
}

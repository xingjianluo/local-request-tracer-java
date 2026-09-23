package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;

import java.util.Optional;

/** 本地有界追踪存储。实现必须线程安全，查询只返回不可变快照。 */
public interface TraceStore {

    void save(TraceSnapshot snapshot);

    Optional<TraceSnapshot> find(String traceId);

    int size();

    int capacity();
}

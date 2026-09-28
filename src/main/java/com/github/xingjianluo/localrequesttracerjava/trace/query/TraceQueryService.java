package com.github.xingjianluo.localrequesttracerjava.trace.query;

import com.github.xingjianluo.localrequesttracerjava.trace.TraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindResult;

/**
 * 查询服务门面：对外承接条件校验，对内委托线程安全的存储。
 * 过滤口径、稳定排序与游标分页的实际计算在存储的一致性快照上完成。
 */
public class TraceQueryService {

    private final TraceStore store;

    public TraceQueryService(TraceStore store) {
        this.store = store;
    }

    public TracePage query(TraceQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (query.fromEpochMs() != null && query.toEpochMs() != null
                && query.fromEpochMs() > query.toEpochMs()) {
            throw new IllegalArgumentException("fromEpochMs must not be after toEpochMs");
        }
        return store.query(query);
    }

    public FindResult findDetailed(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        return store.findDetailed(traceId);
    }
}

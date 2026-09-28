package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.FindResult;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;

import java.util.Optional;

/** 本地有界追踪存储。实现必须线程安全，查询只返回不可变快照。 */
public interface TraceStore {

    void save(TraceSnapshot snapshot);

    Optional<TraceSnapshot> find(String traceId);

    /** 按标识查询的明确结论：命中 / 过期回收 / 容量淘汰 / 从未记录。 */
    default FindResult findDetailed(String traceId) {
        throw new UnsupportedOperationException();
    }

    /** 条件批量查询：时间范围 + 整体结果 + 失败类别，稳定倒序、限量/分页。 */
    default TracePage query(TraceQuery query) {
        throw new UnsupportedOperationException();
    }

    /**
     * 主动清理已超过 TTL 的追踪，返回被清理条数。
     * nowMs 由调用方传入（便于确定性测试）；未启用 TTL 的实现返回 0。
     */
    default int purgeExpired(long nowMs) {
        return 0;
    }

    int size();

    int capacity();
}

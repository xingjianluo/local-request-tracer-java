package com.github.xingjianluo.localrequesttracerjava.trace;

import java.util.Optional;

/**
 * 本地有界追踪存储：容量固定，超限时按确定规则淘汰。
 */
public interface TraceStore {

    /** 获取或创建指定 traceId 的记录。 */
    TraceRecord getOrCreate(String traceId);

    /** 按 traceId 查询。 */
    Optional<TraceRecord> find(String traceId);

    /** 当前容量上限。 */
    int capacity();

    /** 当前保存条数。 */
    int size();
}

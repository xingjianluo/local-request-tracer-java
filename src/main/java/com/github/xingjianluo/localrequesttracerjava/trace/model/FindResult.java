package com.github.xingjianluo.localrequesttracerjava.trace.model;

import java.util.Optional;

/** 按标识查询的结果：明确状态 + 命中时的详情视图。 */
public record FindResult(FindStatus status, TraceDetail detail) {

    public static FindResult found(TraceDetail detail) {
        return new FindResult(FindStatus.FOUND, detail);
    }

    public static FindResult missing(FindStatus status) {
        return new FindResult(status, null);
    }

    public Optional<TraceDetail> optionalDetail() {
        return Optional.ofNullable(detail);
    }
}

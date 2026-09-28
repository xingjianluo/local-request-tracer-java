package com.github.xingjianluo.localrequesttracerjava.trace.query;

import java.util.List;

/**
 * 一页查询结果：本页条目（结束时间倒序，次序稳定）、下一页游标与是否还有后续。
 * cursor 为 null 表示没有下一页。
 */
public record TracePage(List<TraceSummary> items, String nextCursor, boolean hasMore) {
}

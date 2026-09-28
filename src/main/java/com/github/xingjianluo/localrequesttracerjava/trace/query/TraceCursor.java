package com.github.xingjianluo.localrequesttracerjava.trace.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 列表分页游标：编码上一页最后一条记录的排序键
 * {@code (startEpochMs, traceId)}，对外为 URL 安全的不透明字符串。
 *
 * <p>排序方向是 startEpochMs 倒序、traceId 倒序，因此“下一页”的条目
 * 必须严格排在游标键之后（更短的排序位置）。游标只携带稳定排序键，
 * 不依赖易变的写入序号：后台持续写入/淘汰不会让翻页抖动。
 */
public record TraceCursor(long startEpochMs, String traceId) {

    public String encode() {
        String raw = startEpochMs + "|" + traceId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析失败返回 null（调用方按非法参数处理），不抛异常。 */
    public static TraceCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String raw = new String(bytes, StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep <= 0 || sep == raw.length() - 1) {
                return null;
            }
            long ts = Long.parseLong(raw.substring(0, sep));
            String traceId = raw.substring(sep + 1);
            return new TraceCursor(ts, traceId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 判断排序键为 (ts, id) 的条目是否严格排在本游标之后（属于下一页）。
     * 倒序：时间更小者在后；时间相同，traceId 字典序更小者在后。
     */
    public boolean isAfter(long ts, String id) {
        if (ts != startEpochMs) {
            return ts < startEpochMs;
        }
        return id.compareTo(traceId) < 0;
    }
}

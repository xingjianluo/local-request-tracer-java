package com.github.xingjianluo.localrequesttracerjava.trace.query;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 分页游标的不透明编解码：承载上一页最后一条的 (endEpochMs, traceId)。
 *
 * <p>Base64URL 无填充，对调用方不透明；编码自洽保证同条件翻页次序可复现，
 * 不依赖存储当前内容，后台持续写入也不会让结果抖动或漏条/重条。
 */
public final class PageCursor {

    public record CursorKey(long endEpochMs, String traceId) {
    }

    private PageCursor() {
    }

    public static String encode(CursorKey key) {
        byte[] idBytes = key.traceId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + idBytes.length);
        buffer.putLong(key.endEpochMs());
        buffer.put(idBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    /**
     * 解码游标；损坏返回 null，由调用方按无游标处理，
     * 避免外部乱造游标导致接口 5xx。
     */
    public static CursorKey decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            if (raw.length <= Long.BYTES) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            long endEpochMs = buffer.getLong();
            String traceId = new String(
                    buffer.array(), Long.BYTES, raw.length - Long.BYTES, StandardCharsets.UTF_8);
            return new CursorKey(endEpochMs, traceId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.FindResult;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceAttributions;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSummary;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceCursor;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 有界存储：容量固定 + 可选 TTL，写满后按 FIFO 淘汰最早写入的追踪，
 * 存活超过 TTL 的追踪按时间过期回收。两条留存规则同时生效、先到先算：
 * 任一条触发即回收（容量满 → 淘汰最早；到达 TTL → 过期）。
 *
 * <p>确定性保证：
 * <ul>
 *   <li>存入的 {@link TraceSnapshot} 本身不可变，保存即定型；</li>
 *   <li>插入、容量淘汰、TTL 回收、墓碑维护在同一把内部锁下完成，
 *       size 永不超过 capacity，任何查询拿到的要么是完整快照，
 *       要么是明确的“没有”，读不到半截记录；</li>
 *   <li>淘汰顺序严格按写入先后（FIFO），与查询频率无关；</li>
 *   <li>回收时留下有界墓碑（EXPIRED / EVICTED），
 *       使“已过期回收”“被容量淘汰”“从未记录”可区分；
 *       墓碑本身同样有界（不超过容量），并随 TTL 清扫老化；</li>
 *   <li>条件查询在锁内取一致性快照后在锁外过滤排序，
 *       排序键为 (startEpochMs 倒序, traceId 倒序)，稳定可复现，
 *       后台写入/淘汰不会让同一批结果抖动。</li>
 * </ul>
 */
public final class BoundedTraceStore implements TraceStore {

    /** 回收原因，落到墓碑上。 */
    private enum GoneReason {
        EXPIRED,
        EVICTED
    }

    private record TombEntry(GoneReason reason, long createdAtMs) {
    }

    private static final Comparator<TraceSnapshot> ORDER =
            Comparator.comparingLong(TraceSnapshot::startEpochMs).reversed()
                    .thenComparing(TraceSnapshot::traceId, Comparator.reverseOrder());

    private final int capacity;
    private final long ttlMs;
    private final LongSupplier clock;

    private final ConcurrentHashMap<String, TraceSnapshot> data = new ConcurrentHashMap<>();
    private final Deque<String> insertionOrder = new ArrayDeque<>();
    private final Map<String, TombEntry> tombstones = new HashMap<>();
    private final Deque<String> tombOrder = new ArrayDeque<>();
    private final Object lock = new Object();

    public BoundedTraceStore(int capacity) {
        this(capacity, 0L);
    }

    public BoundedTraceStore(int capacity, long ttlMs) {
        this(capacity, ttlMs, System::currentTimeMillis);
    }

    /** 可注入时钟，供确定性测试；生产使用系统时钟。 */
    public BoundedTraceStore(int capacity, long ttlMs, LongSupplier clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (ttlMs < 0) {
            throw new IllegalArgumentException("ttlMs must not be negative");
        }
        this.capacity = capacity;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    /** TTL 是否启用。 */
    public boolean ttlEnabled() {
        return ttlMs > 0;
    }

    @Override
    public void save(TraceSnapshot snapshot) {
        synchronized (lock) {
            long now = clock.getAsLong();
            // 新记录重新存在：旧墓碑（过期/淘汰）作废
            tombstones.remove(snapshot.traceId());
            // 同 traceId 重复保存视为更新：先移除旧的插入位置，避免顺序队列膨胀
            if (data.containsKey(snapshot.traceId())) {
                insertionOrder.remove(snapshot.traceId());
            }
            data.put(snapshot.traceId(), snapshot);
            insertionOrder.addLast(snapshot.traceId());
            // 先做 TTL 回收，再做容量淘汰：两条规则同时生效、先到先算
            purgeExpiredLocked(now);
            while (data.size() > capacity) {
                String evicted = insertionOrder.pollFirst();
                if (evicted == null) {
                    break;
                }
                data.remove(evicted);
                putTombstone(evicted, GoneReason.EVICTED, now);
            }
        }
    }

    @Override
    public Optional<TraceSnapshot> find(String traceId) {
        return findDetailed(traceId).optionalDetail().map(d -> d.trace());
    }

    @Override
    public FindResult findDetailed(String traceId) {
        synchronized (lock) {
            long now = clock.getAsLong();
            TraceSnapshot snapshot = data.get(traceId);
            if (snapshot != null) {
                // 读取时同样先判 TTL：已过期则按过期回收，返回明确状态而非旧数据
                if (isExpired(snapshot, now)) {
                    expireOne(traceId, now);
                } else {
                    return FindResult.found(TraceAttributions.detail(snapshot));
                }
            }
            TombEntry tomb = tombstones.get(traceId);
            if (tomb == null) {
                return FindResult.missing(FindStatus.UNKNOWN);
            }
            return FindResult.missing(tomb.reason() == GoneReason.EXPIRED
                    ? FindStatus.EXPIRED : FindStatus.EVICTED);
        }
    }

    @Override
    public TracePage query(TraceQuery query) {
        long now = clock.getAsLong();
        List<TraceSnapshot> snapshotList;
        synchronized (lock) {
            purgeExpiredLocked(now);
            // 锁内只做一次性拷贝：元素均为不可变快照，锁外处理不再受写入影响
            snapshotList = new ArrayList<>(data.values());
        }

        TraceCursor cursor = query.cursor() == null ? null : TraceCursor.decode(query.cursor());
        if (query.cursor() != null && cursor == null) {
            throw new IllegalArgumentException("invalid cursor");
        }

        List<TraceSummary> matched = new ArrayList<>();
        for (TraceSnapshot s : snapshotList) {
            if (!matches(s, query, cursor)) {
                continue;
            }
            matched.add(TraceAttributions.summarize(s));
        }
        matched.sort(Comparator.comparing((TraceSummary ts) -> ts.trace(), ORDER));

        int limit = normalizeLimit(query.limit());
        boolean hasMore = matched.size() > limit;
        List<TraceSummary> page = hasMore ? new ArrayList<>(matched.subList(0, limit)) : matched;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            TraceSnapshot last = page.get(page.size() - 1).trace();
            nextCursor = new TraceCursor(last.startEpochMs(), last.traceId()).encode();
        }
        return new TracePage(List.copyOf(page), nextCursor, hasMore);
    }

    @Override
    public int purgeExpired(long nowMs) {
        synchronized (lock) {
            int removed = purgeExpiredLocked(nowMs);
            pruneStaleTombstones(nowMs);
            return removed;
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            return data.size();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    // ---- 锁内辅助方法（调用方必须持有 lock）----

    private int purgeExpiredLocked(long now) {
        if (!ttlEnabled()) {
            return 0;
        }
        int removed = 0;
        // insertionOrder 按写入先后排列，过期记录一般集中在队首，逐条判定
        var it = insertionOrder.iterator();
        while (it.hasNext()) {
            String id = it.next();
            TraceSnapshot s = data.get(id);
            if (s != null && isExpired(s, now)) {
                it.remove();
                data.remove(id);
                putTombstone(id, GoneReason.EXPIRED, now);
                removed++;
            }
        }
        return removed;
    }

    private void expireOne(String id, long now) {
        TraceSnapshot s = data.remove(id);
        if (s == null) {
            return;
        }
        insertionOrder.remove(id);
        putTombstone(id, GoneReason.EXPIRED, now);
    }

    private boolean isExpired(TraceSnapshot s, long now) {
        if (!ttlEnabled()) {
            return false;
        }
        // 以 trace 结束时刻为留存起点；存活达到 ttlMs 即回收
        long anchor = s.endEpochMs() > 0 ? s.endEpochMs() : s.startEpochMs();
        return now - anchor >= ttlMs;
    }

    private void putTombstone(String id, GoneReason reason, long now) {
        // 写入路径顺带老化到期墓碑，不把有界性只寄托在定时扫描上
        pruneStaleTombstones(now);
        if (tombstones.put(id, new TombEntry(reason, now)) == null) {
            tombOrder.addLast(id);
        }
        while (tombstones.size() > capacity) {
            String oldest = tombOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            tombstones.remove(oldest);
        }
    }

    private void pruneStaleTombstones(long now) {
        if (!ttlEnabled()) {
            return;
        }
        while (true) {
            String oldest = tombOrder.peekFirst();
            if (oldest == null) {
                return;
            }
            TombEntry entry = tombstones.get(oldest);
            if (entry == null) {
                tombOrder.pollFirst();
                continue;
            }
            // 墓碑留满一个 TTL 周期后老化，保证有界
            if (now - entry.createdAtMs() < ttlMs) {
                return;
            }
            tombOrder.pollFirst();
            tombstones.remove(oldest);
        }
    }

    private boolean matches(TraceSnapshot s, TraceQuery q, TraceCursor cursor) {
        if (q.fromEpochMs() != null && s.startEpochMs() < q.fromEpochMs()) {
            return false;
        }
        if (q.toEpochMs() != null && s.startEpochMs() > q.toEpochMs()) {
            return false;
        }
        if (q.status() != null && s.status() != q.status()) {
            return false;
        }
        if (q.errorCategory() != null) {
            if (s.status() != SpanStatus.FAILED) {
                return false;
            }
            if (q.errorCategory() != TraceAttributions.of(s).errorCategory()) {
                return false;
            }
        }
        if (cursor != null && !cursor.isAfter(s.startEpochMs(), s.traceId())) {
            return false;
        }
        return true;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 100;
        }
        return Math.max(1, Math.min(limit, 500));
    }
}

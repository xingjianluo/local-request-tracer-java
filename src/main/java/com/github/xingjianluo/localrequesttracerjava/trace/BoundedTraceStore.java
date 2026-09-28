package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import com.github.xingjianluo.localrequesttracerjava.trace.query.PageCursor;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TracePage;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceQuery;
import com.github.xingjianluo.localrequesttracerjava.trace.query.TraceSummary;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 有界存储：容量 FIFO 与时间过期两层回收同时生效。
 *
 * <p>回收规则（{@link #save} 落库时一并执行，顺序固定，先到先算）：
 * <ol>
 *   <li>先按保留时长清掉所有已过期记录（以 trace 结束时间 endEpochMs 为准，
 *       {@code endEpochMs + ttlMs <= now} 即过期，ttlMs &lt;= 0 表示不过期）；</li>
 *   <li>再做容量检查，仍超过容量时按写入先后 FIFO 顶掉最早记录。</li>
 * </ol>
 * 被回收的 traceId 进入"回收登记表"（自身有界，FIFO 保留最近 capacity 个），
 * 因此 {@link #lookup} 能区分已过期 / 被顶掉 / 从未记录；同 traceId 重新写入
 * 会把登记撤销。
 *
 * <p>确定性保证：
 * <ul>
 *   <li>存入的 {@link TraceSnapshot} 不可变，保存即定型；所有增删与登记表变更
 *       都在同一把内部锁内完成，任何并发查询拿到的要么是完整记录，要么是明确的没有；</li>
 *   <li>列表查询在锁内拷贝一份一致快照后再过滤排序，排序键为
 *       (endEpochMs 倒序, traceId 倒序)，次序稳定可复现，不受后台持续写入影响。</li>
 * </ul>
 */
public final class BoundedTraceStore implements TraceStore {

    /** 一条回收登记：原因 + 被回收的时间点（毫秒）。 */
    private record Removal(LookupStatus reason, long removedAtMs) {
    }

    private static final Comparator<TraceSnapshot> ORDER =
            Comparator.comparingLong(TraceSnapshot::endEpochMs).reversed()
                    .thenComparing(TraceSnapshot::traceId, Comparator.reverseOrder());

    private final int capacity;
    private final long ttlMs;
    private final Clock clock;

    // data/removed 与两个顺序队列始终只在 lock 内访问：
    // 增删、回收登记、查询拷贝同锁，保证读到的要么是完整记录要么是明确的没有。
    private final java.util.HashMap<String, TraceSnapshot> data = new java.util.HashMap<>();
    private final Deque<String> insertionOrder = new ArrayDeque<>();
    private final Object lock = new Object();

    /** 回收登记表：traceId -> Removal，自身 FIFO 有界，只保留最近的回收结论。 */
    private final java.util.HashMap<String, Removal> removed = new java.util.HashMap<>();
    private final Deque<String> removalOrder = new ArrayDeque<>();

    public BoundedTraceStore(int capacity) {
        this(capacity, 0L, Clock.systemUTC());
    }

    public BoundedTraceStore(int capacity, long ttlMs, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    @Override
    public void save(TraceSnapshot snapshot) {
        synchronized (lock) {
            // 同 traceId 重复保存视为更新：旧的回收登记撤销，插入位置重置
            removed.remove(snapshot.traceId());
            removalOrder.remove(snapshot.traceId());
            if (data.containsKey(snapshot.traceId())) {
                insertionOrder.remove(snapshot.traceId());
            }
            purgeExpiredInLock();
            data.put(snapshot.traceId(), snapshot);
            insertionOrder.addLast(snapshot.traceId());
            while (data.size() > capacity) {
                String evicted = insertionOrder.pollFirst();
                if (evicted == null) {
                    break;
                }
                TraceSnapshot gone = data.remove(evicted);
                if (gone != null) {
                    registerRemoval(evicted, LookupStatus.EVICTED);
                }
            }
        }
    }

    @Override
    public TraceLookup lookup(String traceId) {
        synchronized (lock) {
            purgeExpiredInLock();
            TraceSnapshot snapshot = data.get(traceId);
            if (snapshot != null) {
                return TraceLookup.found(snapshot);
            }
            Removal removal = removed.get(traceId);
            if (removal != null) {
                return TraceLookup.gone(removal.reason());
            }
            return TraceLookup.neverRecorded();
        }
    }

    @Override
    public TracePage query(TraceQuery query) {
        List<TraceSnapshot> copy;
        synchronized (lock) {
            purgeExpiredInLock();
            copy = new ArrayList<>(data.values());
        }
        List<TraceSnapshot> matches = new ArrayList<>();
        for (TraceSnapshot s : copy) {
            if (matches(s, query)) {
                matches.add(s);
            }
        }
        matches.sort(ORDER);

        int fromIndex = 0;
        PageCursor.CursorKey cursor = PageCursor.decode(query.cursor());
        if (cursor != null) {
            // 跳过所有 (end, id) 不早于游标的条目；游标位置本身在上一页已返回
            while (fromIndex < matches.size() && !beforeCursor(matches.get(fromIndex), cursor)) {
                fromIndex++;
            }
        }
        int limit = normalizeLimit(query.limit());
        int toIndex = Math.min(fromIndex + limit, matches.size());
        List<TraceSummary> items = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            items.add(TraceSummary.from(matches.get(i)));
        }
        boolean hasMore = toIndex < matches.size();
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            TraceSummary last = items.get(items.size() - 1);
            nextCursor = PageCursor.encode(new PageCursor.CursorKey(last.endEpochMs(), last.traceId()));
        }
        return new TracePage(List.copyOf(items), nextCursor, hasMore);
    }

    @Override
    public int purgeExpired() {
        synchronized (lock) {
            return purgeExpiredInLock();
        }
    }

    private int purgeExpiredInLock() {
        if (ttlMs <= 0) {
            return 0;
        }
        long now = clock.millis();
        int purged = 0;
        // insertionOrder 按写入先后，过期记录同样最早的排在前面
        for (String traceId : insertionOrder) {
            TraceSnapshot s = data.get(traceId);
            if (s != null && s.endEpochMs() > 0 && s.endEpochMs() + ttlMs <= now) {
                data.remove(traceId);
                registerRemoval(traceId, LookupStatus.EXPIRED);
                purged++;
            }
        }
        if (purged > 0) {
            insertionOrder.removeIf(id -> !data.containsKey(id));
        }
        return purged;
    }

    private void registerRemoval(String traceId, LookupStatus reason) {
        if (!removed.containsKey(traceId)) {
            removalOrder.addLast(traceId);
        }
        removed.put(traceId, new Removal(reason, clock.millis()));
        while (removed.size() > capacity) {
            String oldest = removalOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            removed.remove(oldest);
        }
    }

    private boolean matches(TraceSnapshot s, TraceQuery q) {
        if (q.fromEpochMs() > 0 && s.endEpochMs() < q.fromEpochMs()) {
            return false;
        }
        if (q.toEpochMs() > 0 && s.endEpochMs() > q.toEpochMs()) {
            return false;
        }
        if (q.status() != null && s.status() != q.status()) {
            return false;
        }
        // 指定失败类别时隐含只取失败结果
        if (q.errorCategory() != null
                && (s.status() != SpanStatus.FAILED
                || s.attribution().errorCategory() != q.errorCategory())) {
            return false;
        }
        return true;
    }

    /** 严格排在游标之前：end 更小，或 end 相同而 traceId 更小（倒序下更旧）。 */
    private boolean beforeCursor(TraceSnapshot s, PageCursor.CursorKey cursor) {
        return s.endEpochMs() < cursor.endEpochMs()
                || (s.endEpochMs() == cursor.endEpochMs()
                && s.traceId().compareTo(cursor.traceId()) < 0);
    }

    private int normalizeLimit(int requested) {
        if (requested <= 0) {
            return TraceQuery.DEFAULT_LIMIT;
        }
        return Math.min(requested, TraceQuery.MAX_LIMIT);
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
}

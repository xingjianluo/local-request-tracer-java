package com.github.xingjianluo.localrequesttracerjava.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界存储：容量固定，写满后按 FIFO 淘汰最早写入的追踪。
 *
 * <p>确定性保证：
 * <ul>
 *   <li>存入的 {@link TraceSnapshot} 本身不可变，保存即定型，
 *       高并发写入下不会出现半截记录或跨追踪混合；</li>
 *   <li>插入与淘汰在同一把内部锁下完成，size 永不超过 capacity；</li>
 *   <li>淘汰顺序严格按写入先后（FIFO），与查询频率无关。</li>
 * </ul>
 */
public final class BoundedTraceStore implements TraceStore {

    private final int capacity;
    private final ConcurrentHashMap<String, TraceSnapshot> data = new ConcurrentHashMap<>();
    private final Deque<String> insertionOrder = new ArrayDeque<>();
    private final Object lock = new Object();

    public BoundedTraceStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public void save(TraceSnapshot snapshot) {
        synchronized (lock) {
            // 同 traceId 重复保存视为更新：先移除旧的插入位置，避免顺序队列膨胀
            if (data.containsKey(snapshot.traceId())) {
                insertionOrder.remove(snapshot.traceId());
            }
            data.put(snapshot.traceId(), snapshot);
            insertionOrder.addLast(snapshot.traceId());
            while (data.size() > capacity) {
                String evicted = insertionOrder.pollFirst();
                if (evicted == null) {
                    break;
                }
                data.remove(evicted);
            }
        }
    }

    @Override
    public Optional<TraceSnapshot> find(String traceId) {
        return Optional.ofNullable(data.get(traceId));
    }

    @Override
    public int size() {
        return data.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }
}

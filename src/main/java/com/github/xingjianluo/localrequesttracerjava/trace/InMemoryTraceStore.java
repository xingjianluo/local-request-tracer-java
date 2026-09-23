package com.github.xingjianluo.localrequesttracerjava.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 本地有界追踪存储：access-order LinkedHashMap + 同步块，
 * 容量固定，超限时确定性地淘汰最久未访问的追踪。
 */
public class InMemoryTraceStore implements TraceStore {

    private final int capacity;
    private final Map<String, TraceRecord> records;

    public InMemoryTraceStore() {
        this(1024);
    }

    public InMemoryTraceStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.records = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TraceRecord> eldest) {
                return size() > InMemoryTraceStore.this.capacity;
            }
        };
    }

    @Override
    public TraceRecord getOrCreate(String traceId) {
        synchronized (records) {
            return records.computeIfAbsent(traceId, TraceRecord::new);
        }
    }

    @Override
    public Optional<TraceRecord> find(String traceId) {
        synchronized (records) {
            return Optional.ofNullable(records.get(traceId));
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int size() {
        synchronized (records) {
            return records.size();
        }
    }
}

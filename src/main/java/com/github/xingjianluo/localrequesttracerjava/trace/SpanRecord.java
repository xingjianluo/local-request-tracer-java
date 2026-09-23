package com.github.xingjianluo.localrequesttracerjava.trace;

/**
 * 一次操作（span）的不可变记录。
 */
public final class SpanRecord {

    private final String spanId;
    private final String parentSpanId;
    private final String operation;
    private final String threadName;
    private final long startEpochMillis;
    private final long durationNanos;
    private final boolean failed;
    private final boolean slow;
    private final ErrorCategory errorCategory;

    public SpanRecord(String spanId, String parentSpanId, String operation, String threadName,
                      long startEpochMillis, long durationNanos,
                      boolean failed, boolean slow, ErrorCategory errorCategory) {
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.operation = operation;
        this.threadName = threadName;
        this.startEpochMillis = startEpochMillis;
        this.durationNanos = durationNanos;
        this.failed = failed;
        this.slow = slow;
        this.errorCategory = errorCategory;
    }

    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getOperation() { return operation; }
    public String getThreadName() { return threadName; }
    public long getStartEpochMillis() { return startEpochMillis; }
    public long getDurationNanos() { return durationNanos; }
    public boolean isFailed() { return failed; }
    public boolean isSlow() { return slow; }
    public ErrorCategory getErrorCategory() { return errorCategory; }
}

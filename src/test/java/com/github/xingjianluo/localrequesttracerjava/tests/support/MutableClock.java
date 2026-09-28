package com.github.xingjianluo.localrequesttracerjava.tests.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：手动推进时间，驱动过期判定，避免真实 sleep。
 */
public final class MutableClock extends Clock {

    private volatile long currentMillis;

    public MutableClock(long startMillis) {
        this.currentMillis = startMillis;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(currentMillis);
    }

    @Override
    public long millis() {
        return currentMillis;
    }

    public void setMillis(long millis) {
        this.currentMillis = millis;
    }

    public void advance(long deltaMs) {
        this.currentMillis += deltaMs;
    }
}

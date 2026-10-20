package com.github.sup3rmar10.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Manually advanced clock so tests never sleep. */
final class MutableClock extends Clock {
    private final AtomicLong millis;

    MutableClock(long startMillis) {
        this.millis = new AtomicLong(startMillis);
    }

    MutableClock() {
        this(1_000_000L);
    }

    void advance(long ms) {
        millis.addAndGet(ms);
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}

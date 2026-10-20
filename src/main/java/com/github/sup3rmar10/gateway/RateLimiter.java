package com.github.sup3rmar10.gateway;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Token bucket per key (client IP). Time comes from the injected clock, so tests never sleep. */
public final class RateLimiter {
    public record Decision(boolean allowed, long retryAfterSeconds) { }

    private static final class Bucket {
        double tokens;
        long lastMillis;
    }

    private final Clock clock;
    private final double capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public RateLimiter(Clock clock, double capacity, double refillPerSecond, int maxKeys) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("need capacity >= 1 and refill > 0");
        }
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxKeys = maxKeys;
    }

    public RateLimiter(Clock clock, double capacity, double refillPerSecond) {
        this(clock, capacity, refillPerSecond, 10_000);
    }

    public synchronized Decision tryAcquire(String key) {
        long now = clock.millis();
        Bucket b = buckets.get(key);
        if (b == null) {
            evictIfFull(now);
            b = new Bucket();
            b.tokens = capacity;
            b.lastMillis = now;
            buckets.put(key, b);
        }
        refill(b, now);
        if (b.tokens >= 1) {
            b.tokens -= 1;
            return new Decision(true, 0);
        }
        double secondsNeeded = (1 - b.tokens) / refillPerSecond;
        return new Decision(false, Math.max(1, (long) Math.ceil(secondsNeeded)));
    }

    public synchronized int trackedKeys() {
        return buckets.size();
    }

    private void refill(Bucket b, long now) {
        long elapsed = Math.max(0, now - b.lastMillis);
        b.tokens = Math.min(capacity, b.tokens + elapsed / 1000.0 * refillPerSecond);
        b.lastMillis = now;
    }

    /** Drop buckets that have fully refilled (idle clients); if none are idle, drop arbitrary ones. */
    private void evictIfFull(long now) {
        if (buckets.size() < maxKeys) {
            return;
        }
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
            Bucket b = it.next();
            refill(b, now);
            if (b.tokens >= capacity) {
                it.remove();
            }
        }
        for (Iterator<String> it = buckets.keySet().iterator(); it.hasNext() && buckets.size() >= maxKeys; ) {
            it.next();
            it.remove();
        }
    }
}

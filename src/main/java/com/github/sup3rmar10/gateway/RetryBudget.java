package com.github.sup3rmar10.gateway;

import java.time.Clock;

/**
 * Caps retries at a percentage of recent first-attempt requests (sliding window of 10 buckets), with a small
 * floor so low-traffic routes can still retry. This is what stops a retry storm when an upstream degrades.
 */
public final class RetryBudget {
    private static final int BUCKETS = 10;

    private final Clock clock;
    private final double percent;
    private final int minRetries;
    private final long bucketMillis;
    private final long[] requests = new long[BUCKETS];
    private final long[] retries = new long[BUCKETS];
    private final long[] stamp = new long[BUCKETS];

    public RetryBudget(Clock clock, double percent, int minRetries, long windowMillis) {
        this.clock = clock;
        this.percent = percent;
        this.minRetries = minRetries;
        this.bucketMillis = Math.max(1, windowMillis / BUCKETS);
        java.util.Arrays.fill(stamp, Long.MIN_VALUE);
    }

    public synchronized void recordRequest() {
        requests[slot()]++;
    }

    /** Consumes one retry from the budget if allowed. */
    public synchronized boolean tryAcquireRetry() {
        long id = clock.millis() / bucketMillis;
        long req = 0;
        long ret = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (stamp[i] > id - BUCKETS && stamp[i] <= id) {
                req += requests[i];
                ret += retries[i];
            }
        }
        double allowed = Math.max(minRetries, percent * req / 100.0);
        if (ret + 1 <= allowed) {
            retries[slot()]++;
            return true;
        }
        return false;
    }

    private int slot() {
        long id = clock.millis() / bucketMillis;
        int idx = (int) Math.floorMod(id, (long) BUCKETS);
        if (stamp[idx] != id) {
            stamp[idx] = id;
            requests[idx] = 0;
            retries[idx] = 0;
        }
        return idx;
    }
}

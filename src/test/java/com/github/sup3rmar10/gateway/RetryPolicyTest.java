package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    @Test
    void idempotentMethodsAreRetryable() {
        for (String m : new String[] {"GET", "HEAD", "PUT", "DELETE", "get"}) {
            assertTrue(RetryPolicy.isRetryable(m, null), m);
        }
    }

    @Test
    void nonIdempotentMethodsAreNotRetryableWithoutKey() {
        assertFalse(RetryPolicy.isRetryable("POST", null));
        assertFalse(RetryPolicy.isRetryable("PATCH", null));
        assertFalse(RetryPolicy.isRetryable("POST", "   "));
    }

    @Test
    void postWithIdempotencyKeyIsRetryable() {
        assertTrue(RetryPolicy.isRetryable("POST", "abc-123"));
    }

    @Test
    void capDoublesThenSaturatesAtMax() {
        RetryPolicy p = new RetryPolicy(10, 100, new Random(1));
        assertEquals(10, p.capFor(1));
        assertEquals(20, p.capFor(2));
        assertEquals(40, p.capFor(3));
        assertEquals(80, p.capFor(4));
        assertEquals(100, p.capFor(5));
        assertEquals(100, p.capFor(500), "huge attempt numbers must not overflow");
    }

    @Test
    void jitteredBackoffNeverExceedsCap() {
        RetryPolicy p = new RetryPolicy(10, 100, new Random(7));
        for (int attempt = 1; attempt <= 12; attempt++) {
            for (int i = 0; i < 500; i++) {
                long v = p.backoffMillis(attempt);
                assertTrue(v >= 0 && v <= p.capFor(attempt), "attempt " + attempt + " gave " + v);
            }
        }
    }

    @Test
    void jitterActuallySpreadsAcrossTheRange() {
        RetryPolicy p = new RetryPolicy(100, 100, new Random(3));
        Set<Long> distinct = new HashSet<>();
        long max = 0;
        for (int i = 0; i < 400; i++) {
            long v = p.backoffMillis(1);
            distinct.add(v);
            max = Math.max(max, v);
        }
        assertTrue(distinct.size() > 50, "expected spread, got " + distinct.size());
        assertTrue(max > 90, "upper part of the range must be reachable");
    }

    @Test
    void sameSeedGivesSameSequenceDifferentSeedDiffers() {
        RetryPolicy a = new RetryPolicy(50, 500, new Random(42));
        RetryPolicy b = new RetryPolicy(50, 500, new Random(42));
        RetryPolicy c = new RetryPolicy(50, 500, new Random(43));
        boolean differs = false;
        for (int i = 1; i <= 10; i++) {
            long x = a.backoffMillis(i);
            assertEquals(x, b.backoffMillis(i));
            differs |= x != c.backoffMillis(i);
        }
        assertTrue(differs);
    }
}

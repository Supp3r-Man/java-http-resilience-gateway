package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private final MutableClock clock = new MutableClock();

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        RateLimiter rl = new RateLimiter(clock, 3, 1);
        for (int i = 0; i < 3; i++) {
            assertTrue(rl.tryAcquire("a").allowed());
        }
        assertFalse(rl.tryAcquire("a").allowed());
    }

    @Test
    void refillsWithTime() {
        RateLimiter rl = new RateLimiter(clock, 2, 2);
        rl.tryAcquire("a");
        rl.tryAcquire("a");
        assertFalse(rl.tryAcquire("a").allowed());
        clock.advance(500); // 1 token
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
    }

    @Test
    void retryAfterRoundsUpToWholeSeconds() {
        RateLimiter rl = new RateLimiter(clock, 1, 0.5); // one token per 2 s
        rl.tryAcquire("a");
        assertEquals(2, rl.tryAcquire("a").retryAfterSeconds());
        clock.advance(1_000);
        assertEquals(1, rl.tryAcquire("a").retryAfterSeconds());
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        RateLimiter rl = new RateLimiter(clock, 1, 100); // sub-second wait still reports 1
        rl.tryAcquire("a");
        RateLimiter.Decision d = rl.tryAcquire("a");
        assertFalse(d.allowed());
        assertEquals(1, d.retryAfterSeconds());
    }

    @Test
    void keysAreIndependent() {
        RateLimiter rl = new RateLimiter(clock, 1, 1);
        assertTrue(rl.tryAcquire("10.0.0.1").allowed());
        assertFalse(rl.tryAcquire("10.0.0.1").allowed());
        assertTrue(rl.tryAcquire("10.0.0.2").allowed());
    }

    @Test
    void tokensNeverExceedCapacityAfterLongIdle() {
        RateLimiter rl = new RateLimiter(clock, 2, 10);
        rl.tryAcquire("a");
        clock.advance(3_600_000);
        assertTrue(rl.tryAcquire("a").allowed());
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
    }

    @Test
    void fractionalRefillAccumulates() {
        RateLimiter rl = new RateLimiter(clock, 1, 1);
        rl.tryAcquire("a");
        clock.advance(400);
        assertFalse(rl.tryAcquire("a").allowed());
        clock.advance(600);
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void trackedKeysAreBounded() {
        RateLimiter rl = new RateLimiter(clock, 1, 1, 50);
        for (int i = 0; i < 500; i++) {
            rl.tryAcquire("ip-" + i);
        }
        assertTrue(rl.trackedKeys() <= 50, "tracked " + rl.trackedKeys());
    }
}

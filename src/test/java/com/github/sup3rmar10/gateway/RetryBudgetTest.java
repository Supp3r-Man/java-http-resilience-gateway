package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryBudgetTest {
    private final MutableClock clock = new MutableClock();

    private static int drain(RetryBudget b) {
        int n = 0;
        while (n < 10_000 && b.tryAcquireRetry()) {
            n++;
        }
        return n;
    }

    @Test
    void allowsRetriesUpToPercentOfRequests() {
        RetryBudget b = new RetryBudget(clock, 20, 0, 10_000);
        for (int i = 0; i < 100; i++) {
            b.recordRequest();
        }
        assertEquals(20, drain(b));
    }

    @Test
    void floorAllowsRetriesWhenTrafficIsLow() {
        RetryBudget b = new RetryBudget(clock, 20, 3, 10_000);
        b.recordRequest();
        assertEquals(3, drain(b));
    }

    @Test
    void zeroPercentAndZeroFloorDeniesEverything() {
        RetryBudget b = new RetryBudget(clock, 0, 0, 10_000);
        for (int i = 0; i < 50; i++) {
            b.recordRequest();
        }
        assertFalse(b.tryAcquireRetry());
    }

    @Test
    void budgetRecoversAsWindowSlides() {
        RetryBudget b = new RetryBudget(clock, 10, 0, 10_000);
        for (int i = 0; i < 100; i++) {
            b.recordRequest();
        }
        assertEquals(10, drain(b));
        clock.advance(11_000); // everything expired
        assertFalse(b.tryAcquireRetry(), "no requests in window and no floor");
        for (int i = 0; i < 100; i++) {
            b.recordRequest();
        }
        assertEquals(10, drain(b));
    }

    @Test
    void retriesCountAgainstTheSameWindowAsRequests() {
        RetryBudget b = new RetryBudget(clock, 50, 0, 10_000);
        for (int i = 0; i < 10; i++) {
            b.recordRequest();
        }
        assertEquals(5, drain(b));
        clock.advance(3_000); // still inside the 10 s window
        assertFalse(b.tryAcquireRetry());
        for (int i = 0; i < 10; i++) {
            b.recordRequest();
        }
        assertTrue(b.tryAcquireRetry());
    }
}

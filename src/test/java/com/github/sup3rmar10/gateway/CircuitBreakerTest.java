package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.sup3rmar10.gateway.CircuitBreaker.State;
import java.util.List;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
    private final MutableClock clock = new MutableClock();

    /** window 4, min 4 calls, trips at >= 50% failures, open for 1000 ms, 1 half-open probe. */
    private CircuitBreaker breaker() {
        return new CircuitBreaker(clock, 4, 4, 0.5, 1000, 1);
    }

    private static void call(CircuitBreaker cb, boolean success) {
        long g = cb.acquire();
        assertTrue(g >= 0, "call should be admitted");
        cb.record(g, success);
    }

    private static void trip(CircuitBreaker cb) {
        for (int i = 0; i < 4; i++) {
            call(cb, false);
        }
    }

    @Test
    void startsClosedAndAdmits() {
        CircuitBreaker cb = breaker();
        assertEquals(State.CLOSED, cb.state());
        assertTrue(cb.acquire() >= 0);
    }

    @Test
    void doesNotTripBelowMinimumCalls() {
        CircuitBreaker cb = breaker();
        call(cb, false);
        call(cb, false);
        call(cb, false);
        assertEquals(State.CLOSED, cb.state());
    }

    @Test
    void tripsExactlyAtThreshold() {
        CircuitBreaker cb = breaker();
        call(cb, true);
        call(cb, true);
        call(cb, false);
        call(cb, false); // 2/4 = 0.5 >= 0.5
        assertEquals(State.OPEN, cb.state());
    }

    @Test
    void staysClosedJustBelowThreshold() {
        CircuitBreaker cb = breaker();
        call(cb, true);
        call(cb, true);
        call(cb, true);
        call(cb, false); // 1/4
        assertEquals(State.CLOSED, cb.state());
    }

    @Test
    void openRejectsUntilTimerElapses() {
        CircuitBreaker cb = breaker();
        trip(cb);
        assertEquals(-1, cb.acquire());
        clock.advance(999);
        assertEquals(-1, cb.acquire());
        assertEquals(State.OPEN, cb.state());
    }

    @Test
    void becomesHalfOpenExactlyAtOpenDuration() {
        CircuitBreaker cb = breaker();
        trip(cb);
        clock.advance(1000);
        assertTrue(cb.acquire() >= 0);
        assertEquals(State.HALF_OPEN, cb.state());
    }

    @Test
    void halfOpenAdmitsOnlyConfiguredProbes() {
        CircuitBreaker cb = breaker();
        trip(cb);
        clock.advance(1000);
        assertTrue(cb.acquire() >= 0);
        assertEquals(-1, cb.acquire(), "second concurrent probe must be rejected");
    }

    @Test
    void halfOpenSuccessClosesAndResetsWindow() {
        CircuitBreaker cb = breaker();
        trip(cb);
        clock.advance(1000);
        call(cb, true);
        assertEquals(State.CLOSED, cb.state());
        call(cb, false);
        call(cb, false);
        call(cb, false);
        assertEquals(State.CLOSED, cb.state(), "old failures must not count; min calls applies afresh");
    }

    @Test
    void halfOpenFailureReopensAndRestartsTimer() {
        CircuitBreaker cb = breaker();
        trip(cb);
        clock.advance(1000);
        call(cb, false);
        assertEquals(State.OPEN, cb.state());
        clock.advance(999);
        assertEquals(-1, cb.acquire(), "timer restarts from the reopen");
        clock.advance(1);
        assertTrue(cb.acquire() >= 0);
    }

    @Test
    void staleOutcomeFromEarlierGenerationIsIgnored() {
        CircuitBreaker cb = breaker();
        long straggler = cb.acquire();
        trip(cb);
        clock.advance(1000);
        long probe = cb.acquire();
        cb.record(straggler, true); // admitted while CLOSED; must not close the half-open breaker
        assertEquals(State.HALF_OPEN, cb.state());
        cb.record(probe, true);
        assertEquals(State.CLOSED, cb.state());
    }

    @Test
    void slidingWindowForgetsOldFailures() {
        CircuitBreaker cb = new CircuitBreaker(clock, 4, 4, 0.75, 1000, 1);
        call(cb, false);
        call(cb, false);
        call(cb, true);
        call(cb, true); // 2/4 < 0.75
        call(cb, true);
        call(cb, true); // window now all successes
        call(cb, false); // 1/4
        assertEquals(State.CLOSED, cb.state());
    }

    @Test
    void recordsTransitionsInOrder() {
        CircuitBreaker cb = breaker();
        trip(cb);
        clock.advance(1000);
        call(cb, true);
        List<CircuitBreaker.Transition> t = cb.transitions();
        assertEquals(List.of(State.OPEN, State.HALF_OPEN, State.CLOSED), t.stream().map(x -> x.to()).toList());
    }
}

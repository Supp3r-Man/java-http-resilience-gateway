package com.github.sup3rmar10.gateway;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One backend. Combines three independent admission gates: timed health ejection (consecutive failures,
 * re-admitted through a single half-open probe), the circuit breaker, and an in-flight counter used by the
 * least-in-flight balancer.
 */
public final class Upstream {
    private final String base;
    private final Clock clock;
    private final CircuitBreaker breaker; // may be null (disabled)
    private final int ejectAfter;          // 0 disables ejection
    private final long ejectMillis;
    private final AtomicInteger inFlight = new AtomicInteger();

    private int consecutiveFailures;
    private boolean ejected;
    private long ejectedUntil;
    private boolean probing;

    public Upstream(String base, Clock clock, CircuitBreaker breaker, int ejectAfter, long ejectMillis) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.clock = clock;
        this.breaker = breaker;
        this.ejectAfter = ejectAfter;
        this.ejectMillis = ejectMillis;
    }

    /** A held admission. Must be completed exactly once. */
    public static final class Permit {
        private final Upstream upstream;
        private final long generation;
        private final boolean probe;
        private boolean done;

        private Permit(Upstream upstream, long generation, boolean probe) {
            this.upstream = upstream;
            this.generation = generation;
            this.probe = probe;
        }

        public Upstream upstream() {
            return upstream;
        }

        public boolean isProbe() {
            return probe;
        }

        public void complete(boolean success) {
            if (done) {
                return;
            }
            done = true;
            upstream.complete(this, success);
        }
    }

    public String base() {
        return base;
    }

    public int inFlight() {
        return inFlight.get();
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public synchronized boolean isEjected() {
        return ejected;
    }

    /** Returns a permit, or null when the upstream is ejected, probing, or its breaker rejects the call. */
    public synchronized Permit tryAcquire() {
        boolean probe = false;
        if (ejected) {
            if (clock.millis() < ejectedUntil || probing) {
                return null;
            }
            probe = true;
        }
        long gen = 0;
        if (breaker != null) {
            gen = breaker.acquire();
            if (gen < 0) {
                return null;
            }
        }
        if (probe) {
            probing = true;
        }
        inFlight.incrementAndGet();
        return new Permit(this, gen, probe);
    }

    private synchronized void complete(Permit p, boolean success) {
        inFlight.decrementAndGet();
        if (breaker != null) {
            breaker.record(p.generation, success);
        }
        if (p.probe) {
            probing = false;
            if (success) {
                ejected = false;
                consecutiveFailures = 0;
            } else {
                ejectedUntil = clock.millis() + ejectMillis;
            }
        } else if (!ejected) {
            if (success) {
                consecutiveFailures = 0;
            } else if (ejectAfter > 0 && ++consecutiveFailures >= ejectAfter) {
                ejected = true;
                ejectedUntil = clock.millis() + ejectMillis;
            }
        }
    }

    @Override
    public String toString() {
        return base;
    }
}

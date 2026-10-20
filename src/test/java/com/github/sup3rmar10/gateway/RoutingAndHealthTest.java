package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutingAndHealthTest {
    private final MutableClock clock = new MutableClock();

    private Upstream up(String n, int ejectAfter) {
        return new Upstream("http://" + n, clock, null, ejectAfter, 5000);
    }

    private Route route(Route.Balancer b, Upstream... ups) {
        return new Route("/", b, false, List.of(ups));
    }

    @Test
    void roundRobinRotatesThroughUpstreams() {
        Upstream a = up("a", 0);
        Upstream b = up("b", 0);
        Upstream c = up("c", 0);
        Route r = route(Route.Balancer.ROUND_ROBIN, a, b, c);
        for (Upstream expected : List.of(a, b, c, a, b, c)) {
            Upstream.Permit p = r.pick(Set.of());
            assertSame(expected, p.upstream());
            p.complete(true);
        }
    }

    @Test
    void leastInFlightPicksTheIdleOne() {
        Upstream a = up("a", 0);
        Upstream b = up("b", 0);
        Route r = route(Route.Balancer.LEAST_IN_FLIGHT, a, b);
        Upstream.Permit held1 = a.tryAcquire();
        Upstream.Permit held2 = a.tryAcquire();
        for (int i = 0; i < 4; i++) {
            Upstream.Permit p = r.pick(Set.of());
            assertSame(b, p.upstream());
            p.complete(true);
        }
        held1.complete(true);
        held2.complete(true);
    }

    @Test
    void leastInFlightFollowsLoadNotRotation() {
        Upstream a = up("a", 0);
        Upstream b = up("b", 0);
        Route r = route(Route.Balancer.LEAST_IN_FLIGHT, a, b);
        a.tryAcquire();
        a.tryAcquire();
        b.tryAcquire();
        b.tryAcquire();
        b.tryAcquire();
        // a has 2, b has 3: a wins on every pick regardless of the rotating start index
        for (int i = 0; i < 2; i++) {
            Upstream.Permit p = r.pick(Set.of());
            assertSame(a, p.upstream());
            p.complete(true);
        }
    }

    @Test
    void prefersUntriedUpstreamsAndFallsBackToTried() {
        Upstream a = up("a", 0);
        Upstream b = up("b", 0);
        Route r = route(Route.Balancer.ROUND_ROBIN, a, b);
        assertSame(b, r.pick(Set.of(a)).upstream());
        assertNotNull(r.pick(Set.of(a, b)), "tried-only pool still yields a permit");
    }

    @Test
    void returnsNullWhenNothingAdmits() {
        Upstream a = up("a", 1);
        a.tryAcquire().complete(false); // ejected after one failure
        assertNull(route(Route.Balancer.ROUND_ROBIN, a).pick(Set.of()));
    }

    @Test
    void ejectsAfterConsecutiveFailures() {
        Upstream a = up("a", 3);
        a.tryAcquire().complete(false);
        a.tryAcquire().complete(false);
        assertFalse(a.isEjected());
        a.tryAcquire().complete(false);
        assertTrue(a.isEjected());
        assertNull(a.tryAcquire());
    }

    @Test
    void successResetsTheConsecutiveFailureCount() {
        Upstream a = up("a", 3);
        a.tryAcquire().complete(false);
        a.tryAcquire().complete(false);
        a.tryAcquire().complete(true);
        a.tryAcquire().complete(false);
        a.tryAcquire().complete(false);
        assertFalse(a.isEjected());
    }

    @Test
    void readmittedThroughSingleHalfOpenProbe() {
        Upstream a = up("a", 1);
        a.tryAcquire().complete(false);
        clock.advance(4999);
        assertNull(a.tryAcquire(), "still ejected just before the deadline");
        clock.advance(1);
        Upstream.Permit probe = a.tryAcquire();
        assertNotNull(probe);
        assertTrue(probe.isProbe());
        assertNull(a.tryAcquire(), "only one probe at a time");
        probe.complete(true);
        assertFalse(a.isEjected());
        assertNotNull(a.tryAcquire());
    }

    @Test
    void failedProbeReEjectsForAFullPeriod() {
        Upstream a = up("a", 1);
        a.tryAcquire().complete(false);
        clock.advance(5000);
        a.tryAcquire().complete(false);
        assertTrue(a.isEjected());
        clock.advance(4999);
        assertNull(a.tryAcquire());
        clock.advance(1);
        assertNotNull(a.tryAcquire());
    }

    @Test
    void inFlightCounterTracksPermits() {
        Upstream a = up("a", 0);
        Upstream.Permit p = a.tryAcquire();
        assertEquals(1, a.inFlight());
        p.complete(true);
        p.complete(true); // double complete is a no-op
        assertEquals(0, a.inFlight());
    }
}

package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatewayConfigTest {
    private static GatewayConfig parse(String text) throws Exception {
        Properties p = new Properties();
        p.load(new StringReader(text));
        return GatewayConfig.parse(p);
    }

    private static final String ROUTE = "route.a.prefix=/api\nroute.a.upstreams=http://h1:1, http://h2:2\n";

    @Test
    void parsesRoutesAndDefaults() throws Exception {
        GatewayConfig c = parse(ROUTE);
        assertEquals(1, c.routes.size());
        GatewayConfig.RouteSpec r = c.routes.get(0);
        assertEquals("/api", r.prefix());
        assertEquals(2, r.upstreams().size());
        assertEquals(Route.Balancer.ROUND_ROBIN, r.balancer());
        assertFalse(r.stripPrefix());
        assertEquals(3, c.retryMaxAttempts);
    }

    @Test
    void parsesOverrides() throws Exception {
        GatewayConfig c = parse(ROUTE + "route.a.balancer=LEAST_IN_FLIGHT\nroute.a.strip_prefix=true\n"
                + "retry.max.attempts=5\nretry.statuses=502, 503\nbreaker.failure.rate=0.25\n");
        assertEquals(Route.Balancer.LEAST_IN_FLIGHT, c.routes.get(0).balancer());
        assertTrue(c.routes.get(0).stripPrefix());
        assertEquals(5, c.retryMaxAttempts);
        assertEquals(Set.of(502, 503), c.retryStatuses);
        assertEquals(0.25, c.breakerFailureRate);
    }

    @Test
    void rejectsUnknownKeys() {
        assertThrows(IllegalArgumentException.class, () -> parse(ROUTE + "retry.max.attemps=3\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(ROUTE + "route.a.weight=3\n"));
    }

    @Test
    void rejectsMissingRoutesOrIncompleteRoute() {
        assertThrows(IllegalArgumentException.class, () -> parse("listen.port=1\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("route.a.prefix=/x\n"));
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> parse(ROUTE + "route.a.balancer=random\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(ROUTE + "listen.port=eighty\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(ROUTE + "retry.max.attempts=0\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("route.a.prefix=/x\nroute.a.upstreams=https://h\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("route.a.prefix=x\nroute.a.upstreams=http://h\n"));
    }

    @Test
    void loadsTheShippedExampleConfig() throws Exception {
        GatewayConfig c = GatewayConfig.load(Path.of("config", "gateway.properties"));
        assertEquals(2, c.routes.size());
        assertEquals(Route.Balancer.LEAST_IN_FLIGHT, c.routes.get(0).balancer());
    }

    @Test
    void routeMatchingIsSegmentAwareAndTrailingSlashInsensitive() {
        Route r = new Route("/api/", Route.Balancer.ROUND_ROBIN, false,
                List.of(new Upstream("http://h", new MutableClock(), null, 0, 0)));
        assertEquals("/api", r.prefix());
        assertTrue(r.matches("/api"));
        assertTrue(r.matches("/api/users"));
        assertFalse(r.matches("/apix"));
        assertFalse(r.matches("/ap"));
    }
}

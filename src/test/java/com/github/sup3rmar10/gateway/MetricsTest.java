package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsTest {
    @Test
    void rendersCountersWithHelpAndType() {
        Metrics m = new Metrics();
        m.inc("gateway_requests_total", "route", "/api", "status", "200");
        m.inc("gateway_requests_total", "route", "/api", "status", "200");
        m.inc("gateway_requests_total", "route", "/api", "status", "502");
        String out = m.render(List.of());
        assertTrue(out.contains("# TYPE gateway_requests_total counter\n"));
        assertTrue(out.contains("gateway_requests_total{route=\"/api\",status=\"200\"} 2\n"));
        assertTrue(out.contains("gateway_requests_total{route=\"/api\",status=\"502\"} 1\n"));
    }

    @Test
    void escapesLabelValues() {
        assertEquals("m{k=\"a\\\"b\\\\c\\nd\"}", Metrics.series("m", "k", "a\"b\\c\nd"));
    }

    @Test
    void rendersBreakerStateGauge() {
        MutableClock clock = new MutableClock();
        CircuitBreaker cb = new CircuitBreaker(clock, 2, 2, 0.5, 1000, 1);
        Upstream u = new Upstream("http://h:1", clock, cb, 0, 0);
        String closed = new Metrics().render(List.of(u));
        assertTrue(closed.contains("gateway_breaker_state{upstream=\"http://h:1\"} 0\n"));
        assertTrue(closed.contains("# TYPE gateway_breaker_state gauge\n"));
        for (int i = 0; i < 2; i++) {
            u.tryAcquire().complete(false);
        }
        assertTrue(new Metrics().render(List.of(u)).contains("gateway_breaker_state{upstream=\"http://h:1\"} 1\n"));
    }
}

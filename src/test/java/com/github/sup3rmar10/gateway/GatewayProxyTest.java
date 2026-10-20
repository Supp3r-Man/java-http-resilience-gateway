package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** End-to-end tests: real fake upstreams and a real gateway, both on ephemeral ports. */
class GatewayProxyTest {
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (int i = toClose.size() - 1; i >= 0; i--) {
            toClose.get(i).close();
        }
    }

    private FakeUpstream upstream(String name) throws Exception {
        FakeUpstream u = new FakeUpstream(name);
        toClose.add(u);
        return u;
    }

    private Harness start(GatewayConfig cfg) throws Exception {
        Harness h = new Harness(cfg);
        toClose.add(h);
        return h;
    }

    private Harness start(GatewayConfig cfg, long advanceBy, Clock clock) throws Exception {
        Harness h = new Harness(cfg, advanceBy, clock);
        toClose.add(h);
        return h;
    }

    // ---------------------------------------------------------------- proxying basics

    @Test
    void forwardsMethodPathQueryAndBody() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        HttpResponse<String> r = h.send("POST", "/api/items?x=1&y=two", "hello", "Idempotency-Key", "k1");
        assertEquals(200, r.statusCode());
        assertEquals("up", r.body());
        FakeUpstream.Seen s = up.seen.get(0);
        assertEquals("POST", s.method());
        assertEquals("/api/items?x=1&y=two", s.uri());
        assertEquals("hello", new String(s.body(), StandardCharsets.UTF_8));
    }

    @Test
    void stripPrefixRewritesThePath() throws Exception {
        FakeUpstream up = upstream("up");
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.routes.clear();
        cfg.route("/api", Route.Balancer.ROUND_ROBIN, true, up.url());
        Harness h = start(cfg);
        h.get("/api/items?q=1");
        h.get("/api");
        assertEquals("/items?q=1", up.seen.get(0).uri());
        assertEquals("/", up.seen.get(1).uri());
    }

    @Test
    void unknownRouteIs404WithoutTouchingUpstreams() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        assertEquals(404, h.get("/other").statusCode());
        assertEquals(404, h.get("/apix").statusCode());
        assertEquals(0, up.hits.get());
    }

    @Test
    void copiesStatusAndEndToEndHeadersBack() throws Exception {
        FakeUpstream up = upstream("up");
        up.okStatus = 201;
        up.extraHeaders = java.util.Map.of("X-Up", "1");
        Harness h = start(Harness.baseConfig(up.url()));
        HttpResponse<String> r = h.get("/api/x");
        assertEquals(201, r.statusCode());
        assertEquals("1", r.headers().firstValue("X-Up").orElse(null));
    }

    @Test
    void headResponseHasNoBody() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        HttpResponse<String> r = h.send("HEAD", "/api/x", null);
        assertEquals(200, r.statusCode());
        assertEquals("", r.body());
    }

    @Test
    void largeBodyRoundTrips() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        String big = "x".repeat(200_000);
        assertEquals(200, h.send("PUT", "/api/blob", big).statusCode());
        assertEquals(200_000, up.seen.get(0).body().length);
    }

    // ---------------------------------------------------------------- header handling

    @Test
    void stripsHopByHopRequestHeadersIncludingConnectionListed() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        String req = "GET /api/hop HTTP/1.1\r\nHost: gw\r\nConnection: close, X-Secret-Hop\r\n"
                + "X-Secret-Hop: 1\r\nKeep-Alive: timeout=5\r\nTE: trailers\r\nProxy-Authorization: Basic abc\r\n"
                + "X-Keep: yes\r\n\r\n";
        try (Socket s = new Socket("127.0.0.1", h.gateway.port())) {
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            os.write(req.getBytes(StandardCharsets.US_ASCII));
            os.flush();
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = in.readLine(); // the server keeps the connection open, so read the head only
            assertNotNull(status);
            assertTrue(status.startsWith("HTTP/1.1 200"), status);
        }
        FakeUpstream.Seen seen = up.seen.get(0);
        assertNull(seen.header("X-Secret-Hop"));
        assertNull(seen.header("Keep-Alive"));
        assertNull(seen.header("TE"));
        assertNull(seen.header("Proxy-Authorization"));
        assertEquals("yes", seen.header("X-Keep"));
    }

    @Test
    void stripsHopByHopResponseHeaders() throws Exception {
        FakeUpstream up = upstream("up");
        up.extraHeaders = java.util.Map.of("Proxy-Authenticate", "Basic", "Keep-Alive", "timeout=9", "X-Up", "1");
        Harness h = start(Harness.baseConfig(up.url()));
        HttpResponse<String> r = h.get("/api/x");
        assertTrue(r.headers().firstValue("Proxy-Authenticate").isEmpty());
        assertTrue(r.headers().firstValue("Keep-Alive").isEmpty());
        assertEquals("1", r.headers().firstValue("X-Up").orElse(null));
    }

    @Test
    void setsXForwardedForAndAppendsToExisting() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        h.get("/api/a");
        h.get("/api/b", "X-Forwarded-For", "10.1.2.3");
        assertEquals("127.0.0.1", up.seen.get(0).header("X-Forwarded-For"));
        assertEquals("10.1.2.3, 127.0.0.1", up.seen.get(1).header("X-Forwarded-For"));
    }

    @Test
    void injectsRequestIdOrPreservesTheCallers() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        HttpResponse<String> generated = h.get("/api/a");
        String id = generated.headers().firstValue("X-Request-Id").orElseThrow();
        assertFalse(id.isBlank());
        assertEquals(id, up.seen.get(0).header("X-Request-Id"));
        HttpResponse<String> kept = h.get("/api/b", "X-Request-Id", "req-42");
        assertEquals("req-42", kept.headers().firstValue("X-Request-Id").orElse(null));
        assertEquals("req-42", up.seen.get(1).header("X-Request-Id"));
    }

    // ---------------------------------------------------------------- body limit and rate limit

    @Test
    void declaredOversizeBodyIs413AndNeverReachesUpstream() throws Exception {
        FakeUpstream up = upstream("up");
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.maxBodyBytes = 1000;
        Harness h = start(cfg);
        assertEquals(413, h.send("PUT", "/api/x", "y".repeat(5000)).statusCode());
        assertEquals(0, up.hits.get());
    }

    @Test
    void chunkedOversizeBodyIs413() throws Exception {
        FakeUpstream up = upstream("up");
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.maxBodyBytes = 1000;
        Harness h = start(cfg);
        HttpRequest req = HttpRequest.newBuilder(URI.create(h.base() + "/api/x"))
                .PUT(HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofByteArray(new byte[5000])))
                .build();
        assertEquals(413, h.http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, up.hits.get());
    }

    @Test
    void bodyExactlyAtTheLimitIsAccepted() throws Exception {
        FakeUpstream up = upstream("up");
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.maxBodyBytes = 1000;
        Harness h = start(cfg);
        assertEquals(200, h.send("PUT", "/api/x", "y".repeat(1000)).statusCode());
        assertEquals(413, h.send("PUT", "/api/x", "y".repeat(1001)).statusCode());
    }

    @Test
    void rateLimitReturns429WithRetryAfterThenRecovers() throws Exception {
        FakeUpstream up = upstream("up");
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.rateLimitCapacity = 2;
        cfg.rateLimitRefillPerSec = 1;
        Harness h = start(cfg);
        assertEquals(200, h.get("/api/x").statusCode());
        assertEquals(200, h.get("/api/x").statusCode());
        HttpResponse<String> limited = h.get("/api/x");
        assertEquals(429, limited.statusCode());
        assertEquals("1", limited.headers().firstValue("Retry-After").orElse(null));
        assertEquals(2, up.hits.get());
        h.clock.advance(1000);
        assertEquals(200, h.get("/api/x").statusCode());
        assertEquals(1, h.gateway.metrics().get("gateway_rate_limited_total"));
    }

    // ---------------------------------------------------------------- retries

    @Test
    void getIsRetriedAndSucceeds() throws Exception {
        FakeUpstream up = upstream("up");
        up.failNext.set(1);
        Harness h = start(Harness.baseConfig(up.url()));
        assertEquals(200, h.get("/api/x").statusCode());
        assertEquals(2, up.hits.get());
        assertEquals(1, h.gateway.metrics().get("gateway_retries_total", "route", "/api"));
    }

    @Test
    void putAndDeleteAreRetried() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        up.failNext.set(1);
        assertEquals(200, h.send("PUT", "/api/x", "v").statusCode());
        up.failNext.set(1);
        assertEquals(200, h.send("DELETE", "/api/x", null).statusCode());
        assertEquals(4, up.hits.get());
    }

    @Test
    void postIsNeverRetriedWithoutIdempotencyKey() throws Exception {
        FakeUpstream up = upstream("up");
        up.failNext.set(1);
        Harness h = start(Harness.baseConfig(up.url()));
        HttpResponse<String> r = h.send("POST", "/api/x", "v");
        assertEquals(500, r.statusCode());
        assertEquals(1, up.hits.get());
    }

    @Test
    void postWithIdempotencyKeyIsRetried() throws Exception {
        FakeUpstream up = upstream("up");
        up.failNext.set(1);
        Harness h = start(Harness.baseConfig(up.url()));
        assertEquals(200, h.send("POST", "/api/x", "v", "Idempotency-Key", "abc").statusCode());
        assertEquals(2, up.hits.get());
        assertEquals("abc", up.seen.get(1).header("Idempotency-Key"));
    }

    @Test
    void retriesStopAtMaxAttemptsAndReturnTheLastUpstreamResponse() throws Exception {
        FakeUpstream up = upstream("up");
        up.failing = true;
        Harness h = start(Harness.baseConfig(up.url()));
        HttpResponse<String> r = h.get("/api/x");
        assertEquals(500, r.statusCode());
        assertEquals("fail:up", r.body());
        assertEquals(3, up.hits.get());
    }

    @Test
    void emptyRetryBudgetPreventsRetries() throws Exception {
        FakeUpstream up = upstream("up");
        up.failing = true;
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.retryBudgetPercent = 0;
        cfg.retryBudgetMin = 0;
        Harness h = start(cfg);
        assertEquals(500, h.get("/api/x").statusCode());
        assertEquals(1, up.hits.get());
        assertEquals(1, h.gateway.metrics().get("gateway_retry_budget_exhausted_total"));
    }

    @Test
    void retryPrefersADifferentUpstream() throws Exception {
        FakeUpstream u1 = upstream("u1");
        FakeUpstream u2 = upstream("u2");
        u1.failNext.set(1);
        Harness h = start(Harness.baseConfig(u1.url(), u2.url()));
        HttpResponse<String> r = h.get("/api/x");
        assertEquals(200, r.statusCode());
        assertEquals("u2", r.body());
        assertEquals(1, u1.hits.get());
        assertEquals(1, u2.hits.get());
    }

    @Test
    void backoffSleepsAreTheSeededJitterWithinTheCap() throws Exception {
        FakeUpstream up = upstream("up");
        up.failing = true;
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.retryMaxAttempts = 4;
        Harness h = start(cfg);
        h.get("/api/x");
        RetryPolicy expected = new RetryPolicy(10, 100, new Random(42));
        assertEquals(List.of(expected.backoffMillis(1), expected.backoffMillis(2), expected.backoffMillis(3)), h.sleeps);
        assertTrue(h.sleeps.get(0) <= 10 && h.sleeps.get(1) <= 20 && h.sleeps.get(2) <= 40);
    }

    // ---------------------------------------------------------------- deadlines

    @Test
    void deadlineHeaderIsPropagatedAndClamped() throws Exception {
        FakeUpstream up = upstream("up");
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.deadlineDefaultMs = 4000;
        cfg.deadlineMaxMs = 9000;
        Harness h = start(cfg);
        h.get("/api/a");
        h.get("/api/b", "X-Request-Deadline-Ms", "1500");
        h.get("/api/c", "X-Request-Deadline-Ms", "999999");
        h.get("/api/d", "X-Request-Deadline-Ms", "garbage");
        assertEquals("4000", up.seen.get(0).header("X-Request-Deadline-Ms"));
        assertEquals("1500", up.seen.get(1).header("X-Request-Deadline-Ms"));
        assertEquals("9000", up.seen.get(2).header("X-Request-Deadline-Ms"));
        assertEquals("4000", up.seen.get(3).header("X-Request-Deadline-Ms"));
    }

    @Test
    void deadlineShrinksByTheTimeSpentBackingOff() throws Exception {
        FakeUpstream up = upstream("up");
        up.failNext.set(1);
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.deadlineDefaultMs = 4000;
        cfg.retryBaseBackoffMs = 1000;
        cfg.retryMaxBackoffMs = 1000;
        Harness h = start(cfg);
        assertEquals(200, h.get("/api/x").statusCode());
        long slept = h.sleeps.get(0);
        assertTrue(slept > 0);
        assertEquals("4000", up.seen.get(0).header("X-Request-Deadline-Ms"));
        assertEquals(Long.toString(4000 - slept), up.seen.get(1).header("X-Request-Deadline-Ms"));
    }

    @Test
    void exhaustedDeadlineBetweenRetriesIs504WithoutAnotherUpstreamCall() throws Exception {
        FakeUpstream up = upstream("up");
        up.failing = true;
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.deadlineDefaultMs = 500;
        Harness h = start(cfg, 1000, null); // every backoff burns 1000 ms of injected time
        HttpResponse<String> r = h.get("/api/x");
        assertEquals(504, r.statusCode());
        assertEquals(1, up.hits.get());
    }

    @Test
    void zeroDeadlineFromClientIs504Immediately() throws Exception {
        FakeUpstream up = upstream("up");
        Harness h = start(Harness.baseConfig(up.url()));
        assertEquals(504, h.get("/api/x", "X-Request-Deadline-Ms", "0").statusCode());
        assertEquals(0, up.hits.get());
    }

    @Test
    void slowUpstreamHitsThePerTryTimeout() throws Exception {
        FakeUpstream up = upstream("slow");
        up.gate = new CountDownLatch(1);
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.tryTimeoutMs = 200;
        cfg.retryMaxAttempts = 1;
        Harness h = start(cfg, -1, Clock.systemUTC());
        assertEquals(504, h.get("/api/x").statusCode());
    }

    @Test
    void overallDeadlineCutsOffASlowUpstream() throws Exception {
        FakeUpstream up = upstream("slow");
        up.gate = new CountDownLatch(1);
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.deadlineDefaultMs = 300;
        cfg.tryTimeoutMs = 30_000;
        cfg.retryMaxAttempts = 1;
        Harness h = start(cfg, -1, Clock.systemUTC());
        assertEquals(504, h.get("/api/x").statusCode());
        long sent = Long.parseLong(up.seen.get(0).header("X-Request-Deadline-Ms"));
        assertTrue(sent > 0 && sent <= 300, "sent " + sent);
    }

    @Test
    void timedOutAttemptIsRetriedOnAnotherUpstream() throws Exception {
        FakeUpstream slow = upstream("slow");
        slow.gate = new CountDownLatch(1);
        FakeUpstream ok = upstream("ok");
        GatewayConfig cfg = Harness.baseConfig(slow.url(), ok.url());
        cfg.tryTimeoutMs = 200;
        Harness h = start(cfg, -1, Clock.systemUTC());
        HttpResponse<String> r = h.get("/api/x");
        assertEquals(200, r.statusCode());
        assertEquals("ok", r.body());
    }

    @Test
    void connectionRefusedIs502() throws Exception {
        FakeUpstream up = upstream("gone");
        String url = up.url();
        up.close();
        Harness h = start(Harness.baseConfig(url));
        assertEquals(502, h.send("POST", "/api/x", "v").statusCode());
    }

    // ---------------------------------------------------------------- breaker, ejection, balancing

    @Test
    void breakerOpensFailsFastThenRecoversThroughAProbe() throws Exception {
        FakeUpstream up = upstream("up");
        up.failing = true;
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.retryMaxAttempts = 1;
        cfg.breakerEnabled = true;
        cfg.breakerWindow = 4;
        cfg.breakerMinCalls = 4;
        cfg.breakerFailureRate = 0.5;
        cfg.breakerOpenMs = 5000;
        Harness h = start(cfg);
        for (int i = 0; i < 4; i++) {
            assertEquals(500, h.get("/api/x").statusCode());
        }
        assertEquals(503, h.get("/api/x").statusCode());
        assertEquals(4, up.hits.get(), "open breaker must not call the upstream");
        up.failing = false;
        h.clock.advance(5000);
        assertEquals(200, h.get("/api/x").statusCode());
        assertEquals(200, h.get("/api/x").statusCode());
        assertEquals(3, h.gateway.transitionLog().size(), h.gateway.transitionLog().toString());
        assertEquals(CircuitBreaker.State.CLOSED, h.gateway.upstreams().get(0).breaker().state());
    }

    @Test
    void clientErrorsDoNotCountAsBreakerFailures() throws Exception {
        FakeUpstream up = upstream("up");
        up.okStatus = 404;
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.breakerEnabled = true;
        cfg.breakerWindow = 4;
        cfg.breakerMinCalls = 4;
        Harness h = start(cfg);
        for (int i = 0; i < 10; i++) {
            assertEquals(404, h.get("/api/x").statusCode());
        }
        assertEquals(10, up.hits.get(), "4xx is not retried");
        assertEquals(CircuitBreaker.State.CLOSED, h.gateway.upstreams().get(0).breaker().state());
    }

    @Test
    void failingUpstreamIsEjectedThenReadmittedAfterTheTimeout() throws Exception {
        FakeUpstream bad = upstream("bad");
        FakeUpstream good = upstream("good");
        bad.failing = true;
        GatewayConfig cfg = Harness.baseConfig(bad.url(), good.url());
        cfg.retryMaxAttempts = 1;
        cfg.healthEjectAfter = 3;
        cfg.healthEjectMs = 5000;
        Harness h = start(cfg);
        int errors = 0;
        for (int i = 0; i < 12; i++) {
            if (h.get("/api/x").statusCode() != 200) {
                errors++;
            }
        }
        assertEquals(3, errors);
        assertEquals(3, bad.hits.get(), "no traffic after ejection");
        bad.failing = false;
        h.clock.advance(5000);
        for (int i = 0; i < 4; i++) {
            assertEquals(200, h.get("/api/x").statusCode());
        }
        assertTrue(bad.hits.get() > 3, "probe must have re-admitted it");
        assertFalse(h.gateway.upstreams().get(0).isEjected());
    }

    @Test
    void roundRobinSpreadsEvenly() throws Exception {
        FakeUpstream a = upstream("a");
        FakeUpstream b = upstream("b");
        Harness h = start(Harness.baseConfig(a.url(), b.url()));
        for (int i = 0; i < 10; i++) {
            h.get("/api/x");
        }
        assertEquals(5, a.hits.get());
        assertEquals(5, b.hits.get());
    }

    @Test
    void leastInFlightAvoidsTheBusyUpstream() throws Exception {
        FakeUpstream busy = upstream("busy");
        FakeUpstream idle = upstream("idle");
        busy.gate = new CountDownLatch(1);
        Harness h = start(Harness.baseConfig(Route.Balancer.LEAST_IN_FLIGHT, busy.url(), idle.url()));
        CompletableFuture<HttpResponse<String>> held = CompletableFuture.supplyAsync(() -> {
            try {
                return h.get("/api/held");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(busy.firstArrival.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) {
            assertEquals("idle", h.get("/api/x").body());
        }
        assertEquals(1, busy.hits.get());
        busy.gate.countDown();
        assertEquals("busy", held.get(10, TimeUnit.SECONDS).body());
    }

    // ---------------------------------------------------------------- metrics

    @Test
    void metricsEndpointExposesCountersAndBreakerGauge() throws Exception {
        FakeUpstream up = upstream("up");
        GatewayConfig cfg = Harness.baseConfig(up.url());
        cfg.breakerEnabled = true;
        Harness h = start(cfg);
        h.get("/api/a");
        h.get("/api/b");
        h.get("/nope");
        HttpResponse<String> m = h.get("/metrics");
        assertEquals(200, m.statusCode());
        assertTrue(m.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"));
        String body = m.body();
        assertTrue(body.contains("gateway_requests_total{route=\"/api\",status=\"200\"} 2\n"), body);
        assertTrue(body.contains("gateway_requests_total{route=\"none\",status=\"404\"} 1\n"), body);
        assertTrue(body.contains("gateway_breaker_state{upstream=\"" + up.url() + "\"} 0\n"), body);
    }
}

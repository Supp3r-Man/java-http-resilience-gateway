package com.github.sup3rmar10.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** A real gateway on port 0 with an injected clock, seeded RNG and a recording (non-sleeping) sleeper. */
final class Harness implements AutoCloseable {
    final MutableClock clock = new MutableClock();
    final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    final Gateway gateway;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final GatewayConfig cfg;

    /** advanceBy < 0: advance the clock by the requested sleep; otherwise by that fixed amount. */
    Harness(GatewayConfig cfg, long advanceBy, java.time.Clock clockOverride) throws IOException {
        this.cfg = cfg;
        Gateway.Sleeper sleeper = ms -> {
            sleeps.add(ms);
            clock.advance(advanceBy < 0 ? ms : advanceBy);
        };
        java.time.Clock c = clockOverride == null ? clock : clockOverride;
        gateway = new Gateway(cfg, c, new Random(42), sleeper);
        gateway.start();
    }

    Harness(GatewayConfig cfg) throws IOException {
        this(cfg, -1, null);
    }

    /** Defaults chosen so only the feature under test is active. */
    static GatewayConfig baseConfig(Route.Balancer balancer, String... upstreams) {
        GatewayConfig c = new GatewayConfig();
        c.port = 0;
        c.rateLimitCapacity = 0;
        c.breakerEnabled = false;
        c.healthEjectAfter = 0;
        c.retryMaxAttempts = 3;
        c.retryBaseBackoffMs = 10;
        c.retryMaxBackoffMs = 100;
        c.retryBudgetPercent = 100;
        c.retryBudgetMin = 10;
        c.route("/api", balancer, false, upstreams);
        return c;
    }

    static GatewayConfig baseConfig(String... upstreams) {
        return baseConfig(Route.Balancer.ROUND_ROBIN, upstreams);
    }

    String base() {
        return "http://127.0.0.1:" + gateway.port();
    }

    HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path, String... headers) throws Exception {
        return send("GET", path, null, headers);
    }

    @Override
    public void close() {
        gateway.close();
    }
}

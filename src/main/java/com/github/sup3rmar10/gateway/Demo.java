package com.github.sup3rmar10.gateway;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts three fake upstreams (one flapping), drives the same load through the gateway with three policy
 * settings, and prints an honest comparison. Localhost only; the numbers are illustrative, not a benchmark.
 */
public final class Demo {
    private static final int REQUESTS = 1500;
    private static final int WORKERS = 8;
    private static final long FLAP_PERIOD_MS = 300;

    /** Always answers 200, unless it is the flapping one and currently in a "down" period. */
    private static final class FakeUpstream implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();
        final boolean flapping;
        final long start = System.nanoTime();

        FakeUpstream(boolean flapping) throws Exception {
            this.flapping = flapping;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
            server.setExecutor(Executors.newFixedThreadPool(16, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", ex -> {
                hits.incrementAndGet();
                ex.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                boolean down = flapping && (elapsedMs / FLAP_PERIOD_MS) % 2 == 1;
                byte[] b = (down ? "unavailable" : "ok").getBytes();
                ex.sendResponseHeaders(down ? 503 : 200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record Row(String name, int ok, int total, int upstreamHits, long retries, List<String> transitions) { }

    public static void main(String[] args) throws Exception {
        List<Row> rows = new ArrayList<>();
        rows.add(run("no policies (plain round-robin)", policies(false, false)));
        rows.add(run("retries + budget only", policies(true, false)));
        rows.add(run("retries + budget + breaker + ejection", policies(true, true)));

        System.out.println();
        System.out.println("Fake upstreams: A ok, B ok, C flapping (503 for " + FLAP_PERIOD_MS
                + " ms, ok for " + FLAP_PERIOD_MS + " ms). " + REQUESTS + " GETs, " + WORKERS + " workers.");
        System.out.printf("%-40s %9s %12s %14s %8s %s%n", "scenario", "success", "upstream hits",
                "amplification", "retries", "breaker transitions");
        for (Row r : rows) {
            System.out.printf("%-40s %8.2f%% %12d %14.2f %8d %d%n", r.name(), 100.0 * r.ok() / r.total(),
                    r.upstreamHits(), (double) r.upstreamHits() / r.total(), r.retries(), r.transitions().size());
        }
        Row full = rows.get(2);
        System.out.println();
        System.out.println("Breaker transitions in the full-policy run (first 8 of " + full.transitions().size() + "):");
        full.transitions().stream().limit(8).forEach(t -> System.out.println("  " + t));
        System.out.println();
        System.out.println("Localhost, one laptop, JDK HttpServer. Illustrative only, not a benchmark.");
    }

    private static GatewayConfig policies(boolean retries, boolean breakerAndEjection) {
        GatewayConfig c = new GatewayConfig();
        c.port = 0;
        c.rateLimitCapacity = 0;
        c.retryMaxAttempts = retries ? 3 : 1;
        c.retryBaseBackoffMs = 5;
        c.retryMaxBackoffMs = 50;
        c.breakerEnabled = breakerAndEjection;
        c.breakerWindow = 20;
        c.breakerMinCalls = 4;
        c.breakerOpenMs = 150;
        c.healthEjectAfter = breakerAndEjection ? 5 : 0;
        c.healthEjectMs = 150;
        return c;
    }

    private static Row run(String name, GatewayConfig cfg) throws Exception {
        try (FakeUpstream a = new FakeUpstream(false);
             FakeUpstream b = new FakeUpstream(false);
             FakeUpstream c = new FakeUpstream(true)) {
            cfg.route("/", Route.Balancer.ROUND_ROBIN, false, a.url(), b.url(), c.url());
            try (Gateway gw = new Gateway(cfg)) {
                gw.start();
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                URI uri = URI.create("http://127.0.0.1:" + gw.port() + "/work");
                AtomicInteger sent = new AtomicInteger();
                AtomicInteger ok = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
                for (int w = 0; w < WORKERS; w++) {
                    pool.submit(() -> {
                        while (sent.getAndIncrement() < REQUESTS) {
                            try {
                                HttpResponse<Void> r = client.send(HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding());
                                if (r.statusCode() / 100 == 2) {
                                    ok.incrementAndGet();
                                }
                                Thread.sleep(3);
                            } catch (Exception ignored) {
                                // counted as a failure by not incrementing ok
                            }
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(2, TimeUnit.MINUTES);
                long retries = gw.metrics().get("gateway_retries_total", "route", "/");
                int hits = a.hits.get() + b.hits.get() + c.hits.get();
                System.out.println("finished: " + name);
                return new Row(name, ok.get(), REQUESTS, hits, retries, gw.transitionLog());
            }
        }
    }
}

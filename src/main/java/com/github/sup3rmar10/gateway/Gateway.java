package com.github.sup3rmar10.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The reverse proxy: rate limit, route, deadline, retry loop, and the response copy-back. */
public final class Gateway implements AutoCloseable {
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** What we answer the client with: an upstream response, or a gateway-generated one. */
    private record Result(int status, Map<String, List<String>> headers, byte[] body, boolean failed) {
        static Result synthetic(int status, String message) {
            return new Result(status, Map.of("Content-Type", List.of("text/plain; charset=utf-8")),
                    (message + "\n").getBytes(StandardCharsets.UTF_8), status >= 500);
        }
    }

    private final GatewayConfig cfg;
    private final Clock clock;
    private final Sleeper sleeper;
    private final List<Route> routes = new ArrayList<>();
    private final List<Upstream> allUpstreams = new ArrayList<>();
    private final List<String> transitionLog = Collections.synchronizedList(new ArrayList<>());
    private final RateLimiter limiter;
    private final RetryPolicy retryPolicy;
    private final RetryBudget budget;
    private final Metrics metrics = new Metrics();
    private final HttpClient client;
    private final HttpServer server;
    private final ExecutorService pool;

    public Gateway(GatewayConfig cfg) throws IOException {
        this(cfg, Clock.systemUTC(), new Random(), Thread::sleep);
    }

    public Gateway(GatewayConfig cfg, Clock clock, Random rng, Sleeper sleeper) throws IOException {
        this.cfg = cfg;
        this.clock = clock;
        this.sleeper = sleeper;
        cfg.validate();
        for (GatewayConfig.RouteSpec spec : cfg.routes) {
            List<Upstream> ups = new ArrayList<>();
            for (String url : spec.upstreams()) {
                ups.add(newUpstream(url));
            }
            allUpstreams.addAll(ups);
            routes.add(new Route(spec.prefix(), spec.balancer(), spec.stripPrefix(), ups));
        }
        this.limiter = cfg.rateLimitCapacity > 0
                ? new RateLimiter(clock, cfg.rateLimitCapacity, cfg.rateLimitRefillPerSec) : null;
        this.retryPolicy = new RetryPolicy(cfg.retryBaseBackoffMs, cfg.retryMaxBackoffMs, rng);
        this.budget = new RetryBudget(clock, cfg.retryBudgetPercent, cfg.retryBudgetMin, cfg.retryBudgetWindowMs);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs))
                .build();
        this.pool = Executors.newFixedThreadPool(cfg.serverThreads, r -> {
            Thread t = new Thread(r, "gateway-worker");
            t.setDaemon(true);
            return t;
        });
        this.server = HttpServer.create(new InetSocketAddress(cfg.host, cfg.port), 256);
        this.server.setExecutor(pool);
        this.server.createContext("/", this::handleSafely);
    }

    private Upstream newUpstream(String url) {
        CircuitBreaker breaker = null;
        if (cfg.breakerEnabled) {
            breaker = new CircuitBreaker(clock, cfg.breakerWindow, cfg.breakerMinCalls, cfg.breakerFailureRate,
                    cfg.breakerOpenMs, cfg.breakerHalfOpenPermits, t -> {
                        transitionLog.add(url + " " + t);
                        metrics.inc("gateway_breaker_transitions_total", "upstream", url, "to", t.to().name());
                    });
        }
        return new Upstream(url, clock, breaker, cfg.healthEjectAfter, cfg.healthEjectMs);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Metrics metrics() {
        return metrics;
    }

    public List<Upstream> upstreams() {
        return List.copyOf(allUpstreams);
    }

    public List<String> transitionLog() {
        synchronized (transitionLog) {
            return List.copyOf(transitionLog);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ request handling

    private void handleSafely(HttpExchange ex) {
        try {
            handle(ex);
        } catch (Throwable t) {
            try {
                ex.sendResponseHeaders(500, -1);
            } catch (Throwable ignored) {
                // response already started or client gone
            }
        } finally {
            ex.close();
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String method = ex.getRequestMethod();
        if (path.equals("/metrics") && method.equals("GET")) {
            byte[] b = metrics.render(allUpstreams).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", Metrics.CONTENT_TYPE);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            return;
        }

        String requestId = ex.getRequestHeaders().getFirst("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ex.getResponseHeaders().set("X-Request-Id", requestId);

        Route route = matchRoute(path);
        String label = route == null ? "none" : route.prefix();

        String clientIp = ex.getRemoteAddress().getAddress().getHostAddress();
        if (limiter != null) {
            RateLimiter.Decision d = limiter.tryAcquire(clientIp);
            if (!d.allowed()) {
                ex.getResponseHeaders().set("Retry-After", Long.toString(d.retryAfterSeconds()));
                metrics.inc("gateway_rate_limited_total");
                reply(ex, label, Result.synthetic(429, "rate limit exceeded"));
                return;
            }
        }
        if (route == null) {
            reply(ex, label, Result.synthetic(404, "no route for " + path));
            return;
        }

        String contentLength = ex.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength.trim()) > cfg.maxBodyBytes) {
                    reply413(ex, label);
                    return;
                }
            } catch (NumberFormatException e) {
                reply(ex, label, Result.synthetic(400, "bad Content-Length"));
                return;
            }
        }
        byte[] body = ex.getRequestBody().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, cfg.maxBodyBytes + 1));
        if (body.length > cfg.maxBodyBytes) {
            reply413(ex, label);
            return;
        }

        long budgetMs = cfg.deadlineDefaultMs;
        String dh = ex.getRequestHeaders().getFirst("X-Request-Deadline-Ms");
        if (dh != null) {
            try {
                budgetMs = Long.parseLong(dh.trim());
            } catch (NumberFormatException ignored) {
                // malformed deadline: fall back to the default
            }
        }
        budgetMs = Math.min(budgetMs, cfg.deadlineMaxMs);
        long deadlineAt = clock.millis() + budgetMs;

        boolean retryable = RetryPolicy.isRetryable(method, ex.getRequestHeaders().getFirst("Idempotency-Key"));
        Result result = runAttempts(ex, route, label, path, body, requestId, clientIp, deadlineAt, retryable);
        reply(ex, label, result);
    }

    private Route matchRoute(String path) {
        Route best = null;
        for (Route r : routes) {
            if (r.matches(path) && (best == null || r.prefix().length() > best.prefix().length())) {
                best = r;
            }
        }
        return best;
    }

    private Result runAttempts(HttpExchange ex, Route route, String label, String path, byte[] body,
                               String requestId, String clientIp, long deadlineAt, boolean retryable) {
        budget.recordRequest();
        Set<Upstream> tried = new HashSet<>();
        Result last = null;
        int attempt = 0;
        while (true) {
            long remaining = deadlineAt - clock.millis();
            if (remaining <= 0) {
                return Result.synthetic(504, "deadline exceeded");
            }
            Upstream.Permit permit = route.pick(tried);
            if (permit == null) {
                return last != null ? last : Result.synthetic(503, "no available upstream");
            }
            attempt++;
            if (attempt > 1) {
                metrics.inc("gateway_retries_total", "route", label);
            }
            tried.add(permit.upstream());
            Result r = forward(permit, ex, route, path, body, requestId, clientIp, remaining);
            if (!r.failed()) {
                return r;
            }
            last = r;
            if (!retryable || !cfg.retryStatuses.contains(r.status()) || attempt >= cfg.retryMaxAttempts) {
                return last;
            }
            if (!budget.tryAcquireRetry()) {
                metrics.inc("gateway_retry_budget_exhausted_total");
                return last;
            }
            long wait = Math.min(retryPolicy.backoffMillis(attempt), Math.max(0, deadlineAt - clock.millis()));
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
    }

    private Result forward(Upstream.Permit permit, HttpExchange ex, Route route, String path, byte[] body,
                           String requestId, String clientIp, long remainingMs) {
        boolean ok = false;
        try {
            Result r = call(permit.upstream(), ex, route, path, body, requestId, clientIp, remainingMs);
            ok = !r.failed();
            return r;
        } finally {
            permit.complete(ok);
        }
    }

    private Result call(Upstream upstream, HttpExchange ex, Route route, String path, byte[] body,
                        String requestId, String clientIp, long remainingMs) {
        String upstreamPath = path;
        if (route.stripPrefix() && !route.prefix().equals("/")) {
            upstreamPath = path.substring(route.prefix().length());
            if (upstreamPath.isEmpty()) {
                upstreamPath = "/";
            }
        }
        String query = ex.getRequestURI().getRawQuery();
        URI target = URI.create(upstream.base() + upstreamPath + (query == null ? "" : "?" + query));
        long timeout = Math.max(1, Math.min(remainingMs, cfg.tryTimeoutMs));
        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(Duration.ofMillis(timeout));
        HeaderRules.forwardRequest(ex.getRequestHeaders()).forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));

        List<String> priorXff = ex.getRequestHeaders().get("X-Forwarded-For");
        b.header("X-Forwarded-For", priorXff == null || priorXff.isEmpty()
                ? clientIp : String.join(", ", priorXff) + ", " + clientIp);
        b.header("X-Request-Id", requestId);
        b.header("X-Request-Deadline-Ms", Long.toString(remainingMs));
        b.method(ex.getRequestMethod(), body.length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Result(resp.statusCode(), resp.headers().map(), resp.body(), resp.statusCode() >= 500);
        } catch (HttpTimeoutException e) {
            return Result.synthetic(504, "upstream timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.synthetic(503, "interrupted");
        } catch (IOException | RuntimeException e) {
            return Result.synthetic(502, "upstream error: " + e.getClass().getSimpleName());
        }
    }

    private void reply413(HttpExchange ex, String label) throws IOException {
        ex.getResponseHeaders().set("Connection", "close");
        reply(ex, label, Result.synthetic(413, "request body too large"));
    }

    private void reply(HttpExchange ex, String label, Result r) throws IOException {
        if (r.headers() != null) {
            HeaderRules.forwardResponse(r.headers()).forEach((k, vs) -> {
                if (!k.equalsIgnoreCase("X-Request-Id")) {
                    vs.forEach(v -> ex.getResponseHeaders().add(k, v));
                }
            });
        }
        int status = r.status();
        boolean noBody = ex.getRequestMethod().equals("HEAD") || status == 204 || status == 304
                || r.body().length == 0;
        metrics.inc("gateway_requests_total", "route", label, "status", Integer.toString(status));
        if (noBody) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, r.body().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(r.body());
        }
    }
}

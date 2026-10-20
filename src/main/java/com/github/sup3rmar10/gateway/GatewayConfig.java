package com.github.sup3rmar10.gateway;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Plain-properties configuration. Unknown keys are rejected so typos fail loudly. */
public final class GatewayConfig {
    public record RouteSpec(String name, String prefix, List<String> upstreams, Route.Balancer balancer,
                            boolean stripPrefix) { }

    public String host = "127.0.0.1";
    public int port = 8080;
    public int serverThreads = 64;
    public long maxBodyBytes = 1_048_576;

    public long deadlineDefaultMs = 5000;
    public long deadlineMaxMs = 30_000;
    public long tryTimeoutMs = 2000;
    public long connectTimeoutMs = 1000;

    public int retryMaxAttempts = 3;
    public long retryBaseBackoffMs = 25;
    public long retryMaxBackoffMs = 500;
    public double retryBudgetPercent = 20;
    public int retryBudgetMin = 3;
    public long retryBudgetWindowMs = 10_000;
    public Set<Integer> retryStatuses = new TreeSet<>(Set.of(500, 502, 503, 504));

    public double rateLimitCapacity = 50; // 0 disables
    public double rateLimitRefillPerSec = 25;

    public boolean breakerEnabled = true;
    public int breakerWindow = 20;
    public int breakerMinCalls = 10;
    public double breakerFailureRate = 0.5;
    public long breakerOpenMs = 5000;
    public int breakerHalfOpenPermits = 1;

    public int healthEjectAfter = 3; // 0 disables
    public long healthEjectMs = 5000;

    public final List<RouteSpec> routes = new ArrayList<>();

    /** Builder-style helper used by tests and the demo. */
    public GatewayConfig route(String prefix, Route.Balancer balancer, boolean stripPrefix, String... upstreams) {
        routes.add(new RouteSpec("r" + routes.size(), prefix, List.of(upstreams), balancer, stripPrefix));
        return this;
    }

    public static GatewayConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        }
        return parse(p);
    }

    public static GatewayConfig parse(Properties p) {
        GatewayConfig c = new GatewayConfig();
        Map<String, Map<String, String>> routeAttrs = new LinkedHashMap<>();
        for (String key : new TreeSet<>(p.stringPropertyNames())) {
            String v = p.getProperty(key).trim();
            if (key.startsWith("route.")) {
                int last = key.lastIndexOf('.');
                if (last <= "route.".length() - 1 || last == "route.".length() - 1) {
                    throw new IllegalArgumentException("bad route key: " + key);
                }
                String name = key.substring("route.".length(), last);
                String attr = key.substring(last + 1);
                if (!Set.of("prefix", "upstreams", "balancer", "strip_prefix").contains(attr) || name.isEmpty()) {
                    throw new IllegalArgumentException("unknown route key: " + key);
                }
                routeAttrs.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(attr, v);
                continue;
            }
            try {
                c.set(key, v);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number for " + key + ": " + v);
            }
        }
        routeAttrs.forEach((name, a) -> {
            String prefix = a.get("prefix");
            String ups = a.get("upstreams");
            if (prefix == null || ups == null) {
                throw new IllegalArgumentException("route " + name + " needs prefix and upstreams");
            }
            List<String> list = Arrays.stream(ups.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            c.routes.add(new RouteSpec(name, Route.normalize(prefix), list,
                    Route.Balancer.parse(a.getOrDefault("balancer", "round_robin")),
                    Boolean.parseBoolean(a.getOrDefault("strip_prefix", "false"))));
        });
        c.validate();
        return c;
    }

    private void set(String key, String v) {
        switch (key) {
            case "listen.host" -> host = v;
            case "listen.port" -> port = Integer.parseInt(v);
            case "server.threads" -> serverThreads = Integer.parseInt(v);
            case "max.body.bytes" -> maxBodyBytes = Long.parseLong(v);
            case "deadline.default.ms" -> deadlineDefaultMs = Long.parseLong(v);
            case "deadline.max.ms" -> deadlineMaxMs = Long.parseLong(v);
            case "try.timeout.ms" -> tryTimeoutMs = Long.parseLong(v);
            case "connect.timeout.ms" -> connectTimeoutMs = Long.parseLong(v);
            case "retry.max.attempts" -> retryMaxAttempts = Integer.parseInt(v);
            case "retry.base.backoff.ms" -> retryBaseBackoffMs = Long.parseLong(v);
            case "retry.max.backoff.ms" -> retryMaxBackoffMs = Long.parseLong(v);
            case "retry.budget.percent" -> retryBudgetPercent = Double.parseDouble(v);
            case "retry.budget.min" -> retryBudgetMin = Integer.parseInt(v);
            case "retry.budget.window.ms" -> retryBudgetWindowMs = Long.parseLong(v);
            case "retry.statuses" -> {
                Set<Integer> s = new HashSet<>();
                for (String t : v.split(",")) {
                    if (!t.isBlank()) {
                        s.add(Integer.parseInt(t.trim()));
                    }
                }
                retryStatuses = new TreeSet<>(s);
            }
            case "ratelimit.capacity" -> rateLimitCapacity = Double.parseDouble(v);
            case "ratelimit.refill.per.sec" -> rateLimitRefillPerSec = Double.parseDouble(v);
            case "breaker.enabled" -> breakerEnabled = Boolean.parseBoolean(v);
            case "breaker.window" -> breakerWindow = Integer.parseInt(v);
            case "breaker.min.calls" -> breakerMinCalls = Integer.parseInt(v);
            case "breaker.failure.rate" -> breakerFailureRate = Double.parseDouble(v);
            case "breaker.open.ms" -> breakerOpenMs = Long.parseLong(v);
            case "breaker.half.open.permits" -> breakerHalfOpenPermits = Integer.parseInt(v);
            case "health.eject.after" -> healthEjectAfter = Integer.parseInt(v);
            case "health.eject.ms" -> healthEjectMs = Long.parseLong(v);
            default -> throw new IllegalArgumentException("unknown config key: " + key);
        }
    }

    public void validate() {
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("at least one route is required");
        }
        if (retryMaxAttempts < 1) {
            throw new IllegalArgumentException("retry.max.attempts must be >= 1");
        }
        if (maxBodyBytes < 0 || deadlineDefaultMs < 1 || tryTimeoutMs < 1) {
            throw new IllegalArgumentException("body/deadline/timeout values must be positive");
        }
        for (RouteSpec r : routes) {
            for (String u : r.upstreams()) {
                URI uri = URI.create(u);
                if (!"http".equals(uri.getScheme()) || uri.getHost() == null) {
                    throw new IllegalArgumentException("upstream must be http://host[:port]: " + u);
                }
            }
        }
    }
}

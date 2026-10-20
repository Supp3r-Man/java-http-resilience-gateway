package com.github.sup3rmar10.gateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** A path prefix mapped to a list of upstreams plus a balancing strategy. */
public final class Route {
    public enum Balancer {
        ROUND_ROBIN, LEAST_IN_FLIGHT;

        public static Balancer parse(String s) {
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "round_robin" -> ROUND_ROBIN;
                case "least_in_flight" -> LEAST_IN_FLIGHT;
                default -> throw new IllegalArgumentException("unknown balancer: " + s);
            };
        }
    }

    private final String prefix;
    private final Balancer balancer;
    private final boolean stripPrefix;
    private final List<Upstream> upstreams;
    private final AtomicInteger rr = new AtomicInteger();

    public Route(String prefix, Balancer balancer, boolean stripPrefix, List<Upstream> upstreams) {
        if (upstreams.isEmpty()) {
            throw new IllegalArgumentException("route " + prefix + " has no upstreams");
        }
        this.prefix = normalize(prefix);
        this.balancer = balancer;
        this.stripPrefix = stripPrefix;
        this.upstreams = List.copyOf(upstreams);
    }

    static String normalize(String prefix) {
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException("prefix must start with '/': " + prefix);
        }
        return prefix.length() > 1 && prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    public String prefix() {
        return prefix;
    }

    public boolean stripPrefix() {
        return stripPrefix;
    }

    public List<Upstream> upstreams() {
        return upstreams;
    }

    /** Segment-aware match: /api matches /api and /api/x but not /apix. */
    public boolean matches(String path) {
        if (prefix.equals("/")) {
            return true;
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * Picks an upstream and acquires a permit. Untried upstreams are preferred; already-tried ones are only
     * used when no untried upstream admits the call. Returns null if nothing admits it.
     */
    public Upstream.Permit pick(Set<Upstream> tried) {
        int n = upstreams.size();
        int start = Math.floorMod(rr.getAndIncrement(), n);
        List<Upstream> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(upstreams.get((start + i) % n));
        }
        if (balancer == Balancer.LEAST_IN_FLIGHT) {
            order.sort(Comparator.comparingInt(Upstream::inFlight)); // stable: ties keep rotation order
        }
        for (int pass = 0; pass < 2; pass++) {
            for (Upstream u : order) {
                if (tried.contains(u) != (pass == 1)) {
                    continue;
                }
                Upstream.Permit p = u.tryAcquire();
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }
}

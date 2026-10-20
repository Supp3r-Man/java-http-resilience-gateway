package com.github.sup3rmar10.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Hand-rolled counters and Prometheus text exposition (format 0.0.4). */
public final class Metrics {
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final Map<String, String> HELP = Map.of(
            "gateway_requests_total", "Responses sent to clients, by route and status.",
            "gateway_retries_total", "Upstream attempts beyond the first, by route.",
            "gateway_retry_budget_exhausted_total", "Retries denied because the retry budget was empty.",
            "gateway_rate_limited_total", "Requests rejected with 429.",
            "gateway_breaker_transitions_total", "Circuit breaker transitions, by upstream and new state.",
            "gateway_breaker_state", "Breaker state per upstream: 0 closed, 1 open, 2 half-open.",
            "gateway_upstream_in_flight", "Requests currently in flight per upstream.",
            "gateway_upstream_ejected", "1 if the upstream is currently ejected by health checking.");

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void inc(String name, String... labelKeyValues) {
        counters.computeIfAbsent(series(name, labelKeyValues), k -> new LongAdder()).increment();
    }

    public long get(String name, String... labelKeyValues) {
        LongAdder a = counters.get(series(name, labelKeyValues));
        return a == null ? 0 : a.sum();
    }

    static String series(String name, String... kv) {
        if (kv.length == 0) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('{');
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(kv[i]).append("=\"").append(escape(kv[i + 1])).append('"');
        }
        return sb.append('}').toString();
    }

    static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static int stateValue(CircuitBreaker.State s) {
        return switch (s) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        };
    }

    public String render(Collection<Upstream> upstreams) {
        TreeMap<String, String> samples = new TreeMap<>();
        counters.forEach((k, v) -> samples.put(k, Long.toString(v.sum())));
        for (Upstream u : upstreams) {
            if (u.breaker() != null) {
                samples.put(series("gateway_breaker_state", "upstream", u.base()),
                        Integer.toString(stateValue(u.breaker().state())));
            }
            samples.put(series("gateway_upstream_in_flight", "upstream", u.base()), Integer.toString(u.inFlight()));
            samples.put(series("gateway_upstream_ejected", "upstream", u.base()), u.isEjected() ? "1" : "0");
        }
        Map<String, List<String>> byName = new LinkedHashMap<>();
        samples.forEach((series, value) -> {
            int brace = series.indexOf('{');
            String name = brace < 0 ? series : series.substring(0, brace);
            byName.computeIfAbsent(name, n -> new ArrayList<>()).add(series + " " + value);
        });
        StringBuilder out = new StringBuilder();
        byName.forEach((name, lines) -> {
            out.append("# HELP ").append(name).append(' ').append(HELP.getOrDefault(name, name)).append('\n');
            boolean gauge = name.endsWith("_state") || name.endsWith("_in_flight") || name.endsWith("_ejected");
            out.append("# TYPE ").append(name).append(' ').append(gauge ? "gauge" : "counter").append('\n');
            lines.forEach(l -> out.append(l).append('\n'));
        });
        return out.toString();
    }
}

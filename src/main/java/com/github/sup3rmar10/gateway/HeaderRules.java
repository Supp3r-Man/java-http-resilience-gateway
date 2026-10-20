package com.github.sup3rmar10.gateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Which headers survive a hop through the gateway. */
public final class HeaderRules {
    /** RFC 9110 / 7230 hop-by-hop headers. */
    static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    /** Headers the JDK HttpClient refuses to let us set (it computes them) or that we set ourselves. */
    private static final Set<String> REQUEST_ONLY_DROP = Set.of(
            "host", "content-length", "expect",
            "x-forwarded-for", "x-request-id", "x-request-deadline-ms");

    private HeaderRules() { }

    /** Names listed in any Connection header are hop-by-hop for this message too. */
    static Set<String> connectionListed(Map<String, List<String>> headers) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("connection")) {
                for (String v : e.getValue()) {
                    for (String token : v.split(",")) {
                        String t = token.trim().toLowerCase(Locale.ROOT);
                        if (!t.isEmpty()) {
                            out.add(t);
                        }
                    }
                }
            }
        }
        return out;
    }

    public static Map<String, List<String>> forwardRequest(Map<String, List<String>> in) {
        return filter(in, true);
    }

    public static Map<String, List<String>> forwardResponse(Map<String, List<String>> in) {
        return filter(in, false);
    }

    private static Map<String, List<String>> filter(Map<String, List<String>> in, boolean request) {
        Set<String> listed = connectionListed(in);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : in.entrySet()) {
            String name = e.getKey();
            if (name == null || name.startsWith(":")) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || listed.contains(lower)) {
                continue;
            }
            if (request ? REQUEST_ONLY_DROP.contains(lower) : lower.equals("content-length")) {
                continue;
            }
            out.put(name, new ArrayList<>(e.getValue()));
        }
        return out;
    }
}

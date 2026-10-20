package com.github.sup3rmar10.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class HeaderRulesTest {
    private static Map<String, List<String>> headers(String... kv) {
        Map<String, List<String>> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < kv.length; i += 2) {
            m.computeIfAbsent(kv[i], k -> new ArrayList<>()).add(kv[i + 1]);
        }
        return m;
    }

    @Test
    void stripsStandardHopByHopHeaders() {
        Map<String, List<String>> out = HeaderRules.forwardRequest(headers(
                "Keep-Alive", "timeout=5", "TE", "trailers", "Proxy-Authorization", "x",
                "Upgrade", "websocket", "Transfer-Encoding", "chunked", "Accept", "*/*"));
        assertEquals(List.of("Accept"), List.copyOf(out.keySet()));
    }

    @Test
    void stripsHeadersNamedInConnection() {
        Map<String, List<String>> out = HeaderRules.forwardRequest(headers(
                "Connection", "close, X-Secret-Hop", "X-Secret-Hop", "1", "X-Keep", "2"));
        assertFalse(out.containsKey("X-Secret-Hop"));
        assertFalse(out.containsKey("Connection"));
        assertTrue(out.containsKey("X-Keep"));
    }

    @Test
    void stripIsCaseInsensitive() {
        Map<String, List<String>> out = HeaderRules.forwardResponse(headers("KEEP-ALIVE", "x", "proxy-authenticate", "y"));
        assertTrue(out.isEmpty());
    }

    @Test
    void requestDropsHeadersTheGatewayOwns() {
        Map<String, List<String>> out = HeaderRules.forwardRequest(headers(
                "Host", "client.example", "Content-Length", "5", "X-Forwarded-For", "1.2.3.4",
                "X-Request-Id", "abc", "X-Request-Deadline-Ms", "10", "X-Custom", "v"));
        assertEquals(List.of("X-Custom"), List.copyOf(out.keySet()));
    }

    @Test
    void responseKeepsEndToEndHeadersButNotContentLength() {
        Map<String, List<String>> out = HeaderRules.forwardResponse(headers(
                "Content-Length", "3", "Content-Type", "text/plain", "Cache-Control", "no-store"));
        assertEquals(2, out.size());
        assertFalse(out.containsKey("Content-Length"));
    }

    @Test
    void multiValuedHeadersArePreserved() {
        Map<String, List<String>> out = HeaderRules.forwardResponse(headers("Set-Cookie", "a=1", "Set-Cookie", "b=2"));
        assertEquals(List.of("a=1", "b=2"), out.get("Set-Cookie"));
    }
}

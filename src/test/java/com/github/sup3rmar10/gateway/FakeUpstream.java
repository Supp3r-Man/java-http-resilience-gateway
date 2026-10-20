package com.github.sup3rmar10.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A real embedded HTTP server on port 0 that can fail, hang on a latch, or answer with canned headers. */
final class FakeUpstream implements AutoCloseable {
    record Seen(String method, String uri, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            List<String> v = headers.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    final String name;
    final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-upstream");
        t.setDaemon(true);
        return t;
    });
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger failNext = new AtomicInteger();
    volatile boolean failing;
    volatile int failStatus = 500;
    volatile int okStatus = 200;
    volatile CountDownLatch gate;
    volatile Map<String, String> extraHeaders = Map.of();
    final CountDownLatch firstArrival = new CountDownLatch(1);

    FakeUpstream(String name) throws IOException {
        this.name = name;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        server.setExecutor(pool);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> h.put(k, new ArrayList<>(v)));
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), h, body));
            hits.incrementAndGet();
            firstArrival.countDown();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean fail = failing || failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
            int status = fail ? failStatus : okStatus;
            extraHeaders.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] out = (fail ? "fail:" + name : name).getBytes(StandardCharsets.UTF_8);
            if (ex.getRequestMethod().equals("HEAD") || status == 204) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
            }
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        CountDownLatch g = gate;
        if (g != null) {
            g.countDown();
        }
        server.stop(0);
        pool.shutdownNow();
    }
}

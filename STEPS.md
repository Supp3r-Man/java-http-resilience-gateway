# STEPS

## Part A: Steps taken (in order, including the bugs hit)

1. Chose the scope: a JDK-only reverse proxy (`com.sun.net.httpserver.HttpServer` plus `java.net.http.HttpClient`, Java 17) whose interesting part is the policies, all written by hand and all driven by an injected `java.time.Clock`.
2. Scaffolded the Maven project (`maven.compiler.release=17`, JUnit 5.11.4, surefire 3.5.2), `.gitattributes`, `.gitignore`, MIT license, CI workflow.
3. Wrote the policy classes first, each small and unit-testable on its own: `CircuitBreaker` (count window, generation counter), `Upstream` (ejection with a single half-open probe, in-flight counter, permits), `Route` (prefix match, round-robin and least-in-flight, prefer untried upstreams), `RetryPolicy` (idempotent methods, full jitter), `RetryBudget` (10-bucket sliding window), `RateLimiter` (token bucket per key), `HeaderRules`, `Metrics`, `GatewayConfig`.
4. Wrote `Gateway`: metrics short circuit, request id, route match, rate limit, body limit, deadline, attempt loop, response copy-back.
5. First compile: **bug 1**, `cfg.routes()` does not exist because `routes` is a public field, not a record accessor. Fixed.
6. Wrote the `Demo` main and ran it. **Finding 1**: the "full policy" row showed 0 breaker transitions. The health ejector (3 consecutive failures) always fired before the breaker (min 6 calls), so the breaker never opened. Retuned the demo (eject after 5 consecutive failures, breaker min 4 calls) to try to exercise both mechanisms. Later runs still showed 0 breaker transitions in 3 of 4 runs, so the retune only partly worked. The README shows the variance rather than hiding it.
7. Wrote the test helpers: `MutableClock`, `FakeUpstream` (real `HttpServer` on port 0 with `failNext`, `failing`, a latch gate and canned headers), `Harness` (real gateway on port 0, seeded `Random`, recording sleeper that can advance the injected clock).
8. Tried to create all remaining test files in one shell heredoc batch. **Bug 2**: bash rejected the batch with an unmatched quote error before running anything, so no files were written. Switched to writing each file separately.
9. Wrote the unit tests (breaker, retry policy, budget, limiter, headers, config, metrics, routing/health) and the end-to-end `GatewayProxyTest`.
10. First full run: 93 of 94 green. **Bug 3**: the raw-socket hop-by-hop test hung for 5 s. I had assumed the JDK `HttpServer` closes the connection after a request with `Connection: close`; it does not, so `readAllBytes()` waited for EOF. The test now reads only the status line.
11. Learned along the way (and encoded in `HeaderRules`): the JDK `HttpClient` throws `IllegalArgumentException` if you try to set `Host`, `Content-Length`, `Connection`, `Expect` or `Upgrade`, so those are never copied. Also avoided asserting exact upstream hit counts for "connection closed without a response" cases, because the JDK client has its own internal retry for idempotent requests there.
12. Ran `mvn -q -B verify` three times in a row: all green.
13. Wrote `scripts/mutation_check.py`, which applies 25 single-line mutations one at a time, runs the suite, records which tests fail, and restores the original file in a `finally` block. Results are in the README.
14. Re-ran the demo for the README numbers, wrote the README, and re-ran the full suite after the mutation run to confirm all source files were restored.

## Part B: How to run it yourself

Requirements: JDK 17 or newer, Maven 3.9.

```
# build and test (about 45 s, 94 tests; real sockets on localhost, no sleeping assertions)
mvn -q -B verify

# the demo: three fake upstreams (one flapping), three policy settings, prints a table (about 15-20 s)
java -cp target/classes com.github.sup3rmar10.gateway.Demo

# run the gateway with the example config (edit the upstream URLs first)
java -cp target/classes com.github.sup3rmar10.gateway.Main config/gateway.properties
curl -i http://127.0.0.1:8080/metrics

# reproduce the mutation check (about 25 min; it edits src/main temporarily and restores it)
python scripts/mutation_check.py            # all mutations
python scripts/mutation_check.py --only M05 # one mutation
```

On Windows set `MVN` to the full path of `mvn.cmd` if Maven is not on `PATH`. If you interrupt the mutation script, run `git diff` (or `git checkout -- src/main`) to confirm nothing was left mutated.

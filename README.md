# java-http-resilience-gateway

A small HTTP reverse proxy in Java 17 that uses only the JDK (`com.sun.net.httpserver.HttpServer` for the front
door, `java.net.http.HttpClient` for upstream calls). It exists to show how resilience policies work when you write
them yourself: retries with a budget, a circuit breaker, health ejection, rate limiting and deadline propagation.
It is not a replacement for Envoy, NGINX or a service mesh, and it has never carried real traffic. The only
dependency is JUnit 5 (test scope).

What it does:

- Routes by path prefix (segment-aware: `/api` matches `/api` and `/api/x`, not `/apix`; longest prefix wins) to a
  list of upstreams, balanced round-robin or least-in-flight, optionally stripping the prefix.
- Retries idempotent requests (GET, HEAD, PUT, DELETE, or anything with a non-blank `Idempotency-Key`) on
  configured status codes, with exponential backoff and full jitter, capped by a retry budget.
- Keeps a count-based circuit breaker per upstream and a separate timed ejection for consecutive failures, with a
  single half-open probe to re-admit.
- Token-bucket rate limit per client IP (429 with `Retry-After`), request body cap (413), and a deadline that is
  propagated to the upstream in `X-Request-Deadline-Ms` (504 when it runs out).
- Strips hop-by-hop headers (including those named in `Connection`), adds `X-Forwarded-For` and `X-Request-Id`.
- `GET /metrics` in Prometheus text format (requests, retries, budget exhaustion, rate limiting, breaker
  transitions and state, in-flight and ejection gauges).
- All time goes through an injected `java.time.Clock`, so the tests drive breaker timers, the budget window and
  backoff without sleeping.

## How to run

Requirements: JDK 17 or newer, Maven 3.9.

```bash
mvn -q -B verify          # build and run the 94 tests (about 45 s; real sockets on localhost)

# the demo: three fake upstreams (one flapping), three policy settings, prints a table (about 15-20 s)
java -cp target/classes com.github.sup3rmar10.gateway.Demo

# run the gateway; config/gateway.properties points at 10.0.0.x placeholders, so edit the upstream URLs first
java -cp target/classes com.github.sup3rmar10.gateway.Main config/gateway.properties
```

To try it against something real on your machine, with a throwaway upstream (this is the sequence I ran):

```bash
mkdir /tmp/up && cd /tmp/up && echo hello-from-upstream > hello.txt
python -m http.server 9001 --bind 127.0.0.1 &

cat > try.properties <<'EOF'
listen.port=8080
route.files.prefix=/files
route.files.upstreams=http://127.0.0.1:9001
route.files.strip_prefix=true
EOF

java -cp <path-to>/target/classes com.github.sup3rmar10.gateway.Main try.properties &
curl -i http://127.0.0.1:8080/files/hello.txt     # 200, body "hello-from-upstream", X-Request-Id added
curl http://127.0.0.1:8080/metrics                # gateway_requests_total{route="/files",status="200"} 1
```

I also ran a similar config (two routes, `least_in_flight` on one) against three small Python upstreams (one answering 503 on every
other `/api/flaky` call) and saw: a GET retried to a 200; a POST to the same path returned the 503 unretried;
the same POST with `Idempotency-Key` was retried to a 200; `X-Request-Deadline-Ms: 1500` arrived at the upstream
as 1500; an unknown path gave 404; and 150 requests sent as fast as one `curl` process could produce them
gave 104 x 200 and 46 x 429. The `Retry-After` value is covered by unit tests, not by that manual run.

## Config

A plain `.properties` file. Unknown keys fail at startup (a typo such as `listen.prot` is an
`IllegalArgumentException`, not a silent default). Every key except the routes is optional; defaults are shown.

| Key | Default | Meaning |
|---|---|---|
| `listen.host`, `listen.port` | `127.0.0.1`, `8080` | bind address (port 0 picks a free port) |
| `server.threads` | 64 | worker threads; one blocked thread per in-flight request |
| `max.body.bytes` | 1048576 | larger bodies get 413 (declared or chunked) |
| `deadline.default.ms`, `deadline.max.ms` | 5000, 30000 | used when the client sends no `X-Request-Deadline-Ms`; client values are clamped to max; a non-numeric value falls back to the default |
| `try.timeout.ms`, `connect.timeout.ms` | 2000, 1000 | per-attempt timeout (never more than the remaining deadline) and connect timeout |
| `retry.max.attempts` | 3 | total attempts, including the first |
| `retry.base.backoff.ms`, `retry.max.backoff.ms` | 25, 500 | backoff cap before retry n is `min(max, base * 2^(n-1))` |
| `retry.budget.percent`, `retry.budget.min`, `retry.budget.window.ms` | 20, 3, 10000 | see below |
| `retry.statuses` | `500,502,503,504` | upstream statuses that may be retried |
| `ratelimit.capacity`, `ratelimit.refill.per.sec` | 50, 25 | token bucket per client IP; capacity 0 disables |
| `breaker.enabled` | true | per-upstream circuit breaker |
| `breaker.window`, `breaker.min.calls`, `breaker.failure.rate` | 20, 10, 0.5 | trip when the last `window` outcomes hold at least `rate` failures, once `min.calls` have been seen |
| `breaker.open.ms`, `breaker.half.open.permits` | 5000, 1 | time open before probing, and number of probes allowed |
| `health.eject.after`, `health.eject.ms` | 3, 5000 | eject after N consecutive failures; 0 disables |
| `route.<name>.prefix`, `.upstreams`, `.balancer`, `.strip_prefix` | prefix and upstreams required | `balancer` is `round_robin` (default) or `least_in_flight`; upstreams are comma-separated `http://host[:port]` (no TLS) |

`config/gateway.properties` is a full example with synthetic addresses.

## Request flow

Per request, in this order: `/metrics` short circuit, request id (the caller's `X-Request-Id` is kept, else a UUID),
route match, per-IP rate limit (429), no route (404), body size (413), deadline computation, attempt loop, copy the
response back. A rate-limited client gets 429 even for a path with no route, because the limiter runs before route
lookup.

A response counts as an upstream failure when its status is 500 or above, or the call timed out (504), was refused
or reset (502). 4xx responses are passed through and do not count against the breaker or ejection (a test pins this).

## Design notes

### Retries and the retry budget

Max attempts is a per-request limit: "3 attempts" on every request means up to 3x load on a backend exactly when
it is struggling. The retry budget bounds retries as a share of recent traffic instead. `RetryBudget` keeps a
10-bucket sliding window (default 10 s, so 1 s buckets). Every client request is counted once; a retry is allowed
only if `retries_in_window + 1 <= max(retry.budget.min, percent * requests_in_window / 100)`. With the defaults
that is about 20 percent extra load at most, plus a floor of 3 so a quiet route can still retry. When the budget
says no, the last upstream response is returned and `gateway_retry_budget_exhausted_total` goes up.

Backoff is exponential with full jitter: uniform in `[0, min(max, base * 2^(n-1))]`, so clients that failed together
do not retry together. Tests assert the upper bound, the spread, and that a seeded `Random` is reproducible. A retry
prefers an upstream that has not been tried for this request and falls back to a tried one only if no other admits it.

POST (and PATCH etc.) is retried only with an `Idempotency-Key` header. The gateway cannot check that the upstream
honours the key; it is a contract, not a guarantee.

### Circuit breaker

`CircuitBreaker` is count-based: a ring of the last `window` outcomes. It trips CLOSED to OPEN when at least
`min.calls` outcomes are recorded and the failure share is `>= failure.rate`. After `open.ms` the next call moves
it to HALF_OPEN, which admits `half.open.permits` probes; a successful probe closes it, a failed one re-opens it and
restarts the timer. Every state change clears the window.

Each admitted call carries a generation number, and an outcome from an older generation is ignored. Without that, a
request admitted while CLOSED that finishes after the breaker has gone OPEN or HALF_OPEN could close a half-open
breaker without a real probe. `staleOutcomeFromEarlierGenerationIsIgnored` covers it, and mutation M08 below
checks that the test would notice.

### Health ejection (and why it overlaps with the breaker)

Separately from the breaker, an upstream is ejected for `health.eject.ms` after `health.eject.after` consecutive
failures, then re-admitted through exactly one probe request; failure re-ejects. The two mechanisms overlap: the
ejector reacts to a run of failures, the breaker to a failure rate across the window. In the demo the ejector almost
always fires first (see the demo section). A real system would probably keep one of them, or set the thresholds
deliberately far apart.

### Rate limiting

A token bucket per client IP, refilled from the injected clock, so tests never sleep. `Retry-After` is
`max(1, ceil(seconds until one token))`. The key is the socket peer address. The gateway deliberately does not
trust `X-Forwarded-For` for this, which means that behind another proxy all clients share one bucket. Idle buckets
are evicted once 10000 keys are tracked.

### Deadlines

The client value (or the default) is clamped to `deadline.max.ms` and stored as an absolute time on the injected
clock. Every attempt sends the remaining milliseconds in `X-Request-Deadline-Ms`, so a retry after a backoff sends a
smaller number, and the per-attempt timeout is `min(remaining, try.timeout.ms)`. When no time is left the gateway
answers 504 without calling the upstream.

## Demo output (real run)

`Demo` starts three local fake upstreams (A and B always answer 200, C answers 503 for 300 ms, then 200 for 300 ms,
alternating), then sends the same 1500 GETs from 8 workers through the gateway under three settings. This is the
output of one run on the machine I built this on:

```
Fake upstreams: A ok, B ok, C flapping (503 for 300 ms, ok for 300 ms). 1500 GETs, 8 workers.
scenario                                   success upstream hits  amplification  retries breaker transitions
no policies (plain round-robin)             84.53%         1500           1.00        0 0
retries + budget only                      100.00%         1734           1.16      234 0
retries + budget + breaker + ejection      100.00%         1533           1.02       33 0
```

How to read it: retries lift success from about 85 percent to about 100 percent and cost about 16 percent extra
upstream traffic (inside the 20 percent budget). Adding ejection and the breaker gets the same success for about 2
percent extra traffic, which I attribute to requests being steered away from C while it is failing (see the caveats).

Caveats, because these are easy to over-read:

- Over four runs the "breaker transitions" column for the last row was 0, 0, 0 and 8. The breaker rarely opens in
  this scenario. By the time C has failed 5 times in a row (the ejection threshold in the demo), the breaker's
  20-call window still holds C's successes from its previous healthy period, so the failure share has not reached
  50 percent. My reading is that ejection is doing nearly all of the work in the last row; I did not instrument it
  to prove that. The breaker itself is exercised by the unit tests and by `breakerOpensFailsFastThenRecoversThroughAProbe`.
- Success percentages and hit counts varied between runs (the four runs gave 82.9 to 84.5 percent without policies,
  99.8 to 100 percent with retries only, and about 1.15 to 1.18 amplification). The flapping is time-based on a
  laptop with other things running.
- Everything is on localhost with programmed failures. It is a demonstration of the policies, not a benchmark.

## Tests

94 tests (JUnit 5), all passing with `mvn -q -B verify`:

| Class | Tests | What it covers |
|---|---|---|
| `GatewayProxyTest` | 36 | real gateway on port 0 in front of real embedded fake upstreams: forwarding, headers, body limits, rate limit, retries, budget, jitter, deadlines, timeouts, breaker and ejection end to end, metrics |
| `CircuitBreakerTest` | 12 | thresholds, min calls, open timer, half-open probes, stale generation, sliding window |
| `RoutingAndHealthTest` | 10 | balancers, preferring untried upstreams, ejection and the single probe |
| `RateLimiterTest` | 8 | refill, capacity cap, `Retry-After`, per-key isolation, eviction |
| `RetryPolicyTest` | 7 | retryable methods, cap growth and saturation, jitter bound and determinism |
| `GatewayConfigTest` | 7 | parsing, unknown keys, validation |
| `HeaderRulesTest` | 6 | hop-by-hop and `Connection`-listed headers |
| `RetryBudgetTest` | 5 | percent, floor, window sliding |
| `MetricsTest` | 3 | text exposition format |

The tests use a `MutableClock`, a recording sleeper that can advance the clock, a seeded `Random`, and a
`FakeUpstream` (a real `HttpServer` with `failNext`, `failing`, a latch gate and canned headers). Only the tests
that need a real timeout (a latch-blocked upstream) really wait, and they assert the outcome (504), not the timing.

## Mutation check

Passing tests prove little if they would also pass on broken code, so I applied 25 behaviour-defining mutations one
at a time (single textual replacements run by `scripts/mutation_check.py`, which restores each file afterwards),
ran `mvn test` for each, and recorded whether any test failed. This is a hand-rolled check, not PIT, and the
mutations are the ones I thought of, so it says nothing about bugs I did not think of.

**Result: 24 of 25 killed, 1 survived.**

| # | Mutation | Result | First failing test(s) |
|---|---|---|---|
| M01 | retry POST (and every other method) even without `Idempotency-Key` | killed | `GatewayProxyTest.postIsNeverRetriedWithoutIdempotencyKey`, `RetryPolicyTest.nonIdempotentMethodsAreNotRetryableWithoutKey` |
| M02 | ignore `Idempotency-Key` (never retry POST) | killed | `GatewayProxyTest.postWithIdempotencyKeyIsRetried`, `RetryPolicyTest.postWithIdempotencyKeyIsRetryable` |
| M03 | remove the jitter upper bound (3x cap) | killed | `GatewayProxyTest.backoffSleepsAreTheSeededJitterWithinTheCap`, `RetryPolicyTest.jitteredBackoffNeverExceedsCap` |
| M04 | backoff cap removed (unbounded exponential) | killed | `RetryPolicyTest.capDoublesThenSaturatesAtMax` |
| M05 | breaker threshold off-by-one (`>=` becomes `>`) | killed | `CircuitBreakerTest.tripsExactlyAtThreshold` |
| M06 | breaker minimum-calls off-by-one | killed | `CircuitBreakerTest.becomesHalfOpenExactlyAtOpenDuration`, `CircuitBreakerTest.halfOpenAdmitsOnlyConfiguredProbes` |
| M07 | failed half-open probe closes the breaker | killed | `CircuitBreakerTest.halfOpenFailureReopensAndRestartsTimer` |
| M08 | stale-generation outcomes no longer ignored | killed | `CircuitBreakerTest.staleOutcomeFromEarlierGenerationIsIgnored` |
| M09 | forget standard hop-by-hop stripping | killed | `GatewayProxyTest.stripsHopByHopRequestHeadersIncludingConnectionListed`, `GatewayProxyTest.stripsHopByHopResponseHeaders` |
| M10 | ignore headers named in the `Connection` header | killed | `GatewayProxyTest.stripsHopByHopRequestHeadersIncludingConnectionListed`, `HeaderRulesTest.stripsHeadersNamedInConnection` |
| M11 | no retry budget (always allow) | killed | `GatewayProxyTest.emptyRetryBudgetPreventsRetries`, `RetryBudgetTest.allowsRetriesUpToPercentOfRequests` |
| M12 | wrong `Retry-After` (floor instead of ceil, no minimum) | killed | `RateLimiterTest.retryAfterIsAtLeastOneSecond` |
| M13 | token bucket never caps at capacity | killed | `RateLimiterTest.tokensNeverExceedCapacityAfterLongIdle` |
| M14 | deadline not propagated downstream | killed | `GatewayProxyTest.deadlineHeaderIsPropagatedAndClamped`, `GatewayProxyTest.deadlineShrinksByTheTimeSpentBackingOff` |
| M15 | propagate the original budget instead of the remaining time | killed | `GatewayProxyTest.deadlineHeaderIsPropagatedAndClamped`, `GatewayProxyTest.deadlineShrinksByTheTimeSpentBackingOff` |
| M16 | `X-Forwarded-For` does not append the client IP | killed | `GatewayProxyTest.setsXForwardedForAndAppendsToExisting` |
| M17 | max body check off-by-one (`>=` instead of `>`) | killed | `GatewayProxyTest.bodyExactlyAtTheLimitIsAccepted` |
| M18 | every upstream outcome recorded as success (breaker and ejection blind) | killed | `GatewayProxyTest.breakerOpensFailsFastThenRecoversThroughAProbe`, `GatewayProxyTest.failingUpstreamIsEjectedThenReadmittedAfterTheTimeout` |
| M19 | per-try timeout ignores the remaining deadline | killed | `GatewayProxyTest.overallDeadlineCutsOffASlowUpstream` |
| M20 | deadline exhaustion never checked between attempts | **survived** | no test fails, see below |
| M21 | request id not echoed to the client | killed | `GatewayProxyTest.injectsRequestIdOrPreservesTheCallers` |
| M22 | retry does not prefer an untried upstream | killed | `RoutingAndHealthTest.prefersUntriedUpstreamsAndFallsBackToTried` |
| M23 | least-in-flight balancer does not sort by load | killed | `GatewayProxyTest.leastInFlightAvoidsTheBusyUpstream`, `RoutingAndHealthTest.leastInFlightFollowsLoadNotRotation` |
| M24 | half-open probe not limited to one in flight | killed | `RoutingAndHealthTest.readmittedThroughSingleHalfOpenProbe` |
| M25 | ejected upstream is never re-admitted | killed | `GatewayProxyTest.failingUpstreamIsEjectedThenReadmittedAfterTheTimeout`, `RoutingAndHealthTest.readmittedThroughSingleHalfOpenProbe` |

The script reports at most the first two failing tests per mutation, so "first failing test(s)" is not the full list.

Notes on the result:

- **M20 is a real gap, probably a weak one.** The mutation changes the `remaining <= 0` check to
  `remaining < -1000000`. Two tests exist for the behaviour (`exhaustedDeadlineBetweenRetriesIs504WithoutAnotherUpstreamCall`
  and `zeroDeadlineFromClientIs504Immediately`) and both still pass with the check gone. My explanation, not
  separately verified: without the check the attempt still happens, but its per-try timeout is floored at 1 ms, which
  almost always expires before the request reaches the fake upstream, so the client still sees 504 and the upstream hit
  count still looks like 0 or 1. If that is right, the check mainly saves a pointless attempt, and the tests cannot
  tell the two apart without being timing-dependent. I left it as a survivor rather than write a flaky test.
- Several mutations are killed by both a unit test and an end-to-end test (M01, M02, M03, M10, M11, M23,
  M25). The ones killed by a single test (M04, M05, M07, M08, M12, M13, M16, M17, M19, M21, M22, M24) are the ones
  I would worry about if that test were refactored.
- The 25 mutations cover retry policy, breaker, headers, budget, rate limiter, deadlines, routing and ejection, but not
  `GatewayConfig` parsing, `Metrics` output or the response copy-back path. Those are exercised by tests that I did
  not mutation-check.

Reproduce: `python scripts/mutation_check.py` (set `MVN` to the full path of `mvn.cmd` if `mvn` is not on PATH).
It takes roughly 20 minutes and restores every file in a `finally` block; `--only M05` runs one mutation.

## Limits / not verified

- Each in-flight request holds one server thread and its whole body in memory (retries need to replay it), so there
  is no streaming and large uploads are the wrong workload. No HTTP/2, no TLS (upstreams must be `http://`), no
  connection pool tuning, no WebSocket or `Upgrade` support.
- Rate limiting keys on the socket peer address, so behind another proxy every client looks like one IP. The key
  isolation is a unit test; I could not test two distinct client IPs end to end on localhost.
- HEAD responses are sent without `Content-Length` (a `HttpServer` constraint), so HEAD fidelity is incomplete.
- The retry budget, breaker state and rate limits are local to one process. With N gateway replicas the aggregate
  retry budget is N times larger.
- The breaker and the ejector overlap (see above). The thresholds in the demo were picked for a 1500-request
  localhost run, not derived from any SLO or load test.
- No latency-based outlier detection, no hedged requests, no active health checks, no request logging, no latency
  histogram.
- Nothing has been run behind real TLS, real DNS or a real network partition. The "failures" are programmed 503s,
  refused ports and latch-blocked handlers. The demo numbers come from one laptop and are illustrative.
- CI: the workflow (`.github/workflows/ci.yml`, Java 17 and 21, `mvn verify` then the demo) is included. CI ran green on the first run on GitHub Actions (Java 17 and 21, ubuntu-latest, 2026-10-20). Locally, `mvn -q -B verify` passes with 94 tests
  on JDK 17.

## Layout

```
src/main/java/com/github/sup3rmar10/gateway/   Gateway, Route, Upstream, CircuitBreaker, RetryPolicy, RetryBudget,
                                               RateLimiter, HeaderRules, Metrics, GatewayConfig, Main, Demo
src/test/java/...                              unit tests plus end-to-end tests against embedded fake upstreams
config/gateway.properties                      example configuration (synthetic addresses)
scripts/mutation_check.py                      the mutation check described above
STEPS.md                                       how this was built, and how to run it yourself
```

Clone: `git clone https://github.com/Supp3r-Man/java-http-resilience-gateway.git`

MIT licensed.

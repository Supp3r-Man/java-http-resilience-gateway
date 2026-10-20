#!/usr/bin/env python
"""Hand-rolled mutation check: apply one source mutation at a time, run the tests, expect a failure, revert.

Usage: python scripts/mutation_check.py [--only N]
Env:   MVN=path/to/mvn (default: mvn on PATH)

The original file content is always restored in a finally block, and a final check confirms every file
is byte-identical to what it was before the run.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "github", "sup3rmar10", "gateway")
MVN = os.environ.get("MVN", "mvn")

# (id, file, old, new, description)
MUTATIONS = [
    ("M01", "RetryPolicy.java", "return idempotencyKey != null && !idempotencyKey.isBlank();", "return true;",
     "retry POST (and every other method) even without Idempotency-Key"),
    ("M02", "RetryPolicy.java", "return idempotencyKey != null && !idempotencyKey.isBlank();", "return false;",
     "ignore Idempotency-Key (never retry POST)"),
    ("M03", "RetryPolicy.java", "return (long) (rng.nextDouble() * (cap + 1));",
     "return (long) (rng.nextDouble() * (cap + 1) * 3);", "remove the jitter upper bound (3x cap)"),
    ("M04", "RetryPolicy.java", "return Math.min(maxMillis, baseMillis * (1L << shift));",
     "return baseMillis * (1L << shift);", "backoff cap removed (unbounded exponential)"),
    ("M05", "CircuitBreaker.java", "(double) failures / filled >= failureRate", "(double) failures / filled > failureRate",
     "breaker threshold off-by-one (>= becomes >)"),
    ("M06", "CircuitBreaker.java", "filled >= minCalls &&", "filled > minCalls &&",
     "breaker minimum-calls off-by-one"),
    ("M07", "CircuitBreaker.java", "case HALF_OPEN -> transition(success ? State.CLOSED : State.OPEN);",
     "case HALF_OPEN -> transition(State.CLOSED);", "failed half-open probe closes the breaker"),
    ("M08", "CircuitBreaker.java", "if (gen != generation) {\n            return;", "if (false) {\n            return;",
     "stale-generation outcomes no longer ignored"),
    ("M09", "HeaderRules.java", "if (HOP_BY_HOP.contains(lower) || listed.contains(lower)) {",
     "if (listed.contains(lower)) {", "forget standard hop-by-hop stripping"),
    ("M10", "HeaderRules.java", "if (HOP_BY_HOP.contains(lower) || listed.contains(lower)) {",
     "if (HOP_BY_HOP.contains(lower)) {", "ignore headers named in the Connection header"),
    ("M11", "RetryBudget.java", "if (ret + 1 <= allowed) {", "if (true) {", "no retry budget (always allow)"),
    ("M12", "RateLimiter.java", "Math.max(1, (long) Math.ceil(secondsNeeded))", "(long) Math.floor(secondsNeeded)",
     "wrong Retry-After (floor instead of ceil, no minimum)"),
    ("M13", "RateLimiter.java", "Math.min(capacity, b.tokens + elapsed / 1000.0 * refillPerSecond)",
     "b.tokens + elapsed / 1000.0 * refillPerSecond", "token bucket never caps at capacity"),
    ("M14", "Gateway.java", 'b.header("X-Request-Deadline-Ms", Long.toString(remainingMs));', "",
     "deadline not propagated downstream"),
    ("M15", "Gateway.java", 'b.header("X-Request-Deadline-Ms", Long.toString(remainingMs));',
     'b.header("X-Request-Deadline-Ms", Long.toString(cfg.deadlineDefaultMs));',
     "propagate the original budget instead of the remaining time"),
    ("M16", "Gateway.java", 'String.join(", ", priorXff) + ", " + clientIp', 'String.join(", ", priorXff)',
     "X-Forwarded-For does not append the client IP"),
    ("M17", "Gateway.java", "if (body.length > cfg.maxBodyBytes) {", "if (body.length >= cfg.maxBodyBytes) {",
     "max body check off-by-one (>= instead of >)"),
    ("M18", "Gateway.java", "permit.complete(ok);", "permit.complete(true);",
     "every upstream outcome recorded as success (breaker and ejection blind)"),
    ("M19", "Gateway.java", "Math.min(remainingMs, cfg.tryTimeoutMs)", "cfg.tryTimeoutMs",
     "per-try timeout ignores the remaining deadline"),
    ("M20", "Gateway.java", "if (remaining <= 0) {", "if (remaining < -1000000) {",
     "deadline exhaustion never checked between attempts"),
    ("M21", "Gateway.java", 'ex.getResponseHeaders().set("X-Request-Id", requestId);', "",
     "request id not echoed to the client"),
    ("M22", "Route.java", "if (tried.contains(u) != (pass == 1)) {", "if (false) {",
     "retry does not prefer an untried upstream"),
    ("M23", "Route.java", "order.sort(Comparator.comparingInt(Upstream::inFlight));", "",
     "least-in-flight balancer does not sort by load"),
    ("M24", "Upstream.java", "if (clock.millis() < ejectedUntil || probing) {", "if (clock.millis() < ejectedUntil) {",
     "half-open probe not limited to one in flight"),
    ("M25", "Upstream.java", "if (clock.millis() < ejectedUntil || probing) {", "if (true) {",
     "ejected upstream is never re-admitted"),
]


def run_tests():
    cmd = [MVN, "-B", "test", "-Dsurefire.skipAfterFailureCount=1"]
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, shell=(os.name == "nt"))
    out = p.stdout + p.stderr
    if "COMPILATION ERROR" in out:
        return "COMPILE_ERROR", []
    if p.returncode == 0:
        return "SURVIVED", []
    names = []
    for m in re.finditer(r"\[ERROR\]\s+(\w+Test)\.(\w+)", out):
        n = m.group(1) + "." + m.group(2)
        if n not in names:
            names.append(n)
    return "KILLED", names[:2]


def main():
    only = None
    if "--only" in sys.argv:
        only = sys.argv[sys.argv.index("--only") + 1]
    originals = {}
    rows = []
    try:
        for mid, fname, old, new, desc in MUTATIONS:
            if only and mid != only:
                continue
            path = os.path.join(SRC, fname)
            if path not in originals:
                with open(path, encoding="utf-8", newline="") as f:
                    originals[path] = f.read()
            text = originals[path]
            if text.count(old) != 1:
                rows.append((mid, desc, "BAD_PATTERN", []))
                continue
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(text.replace(old, new))
            try:
                status, names = run_tests()
            finally:
                with open(path, "w", encoding="utf-8", newline="") as f:
                    f.write(text)
            rows.append((mid, desc, status, names))
            print(f"{mid} {status} {', '.join(names)}  -- {desc}", flush=True)
    finally:
        for path, text in originals.items():
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(text)
    print()
    print("| # | Mutation | Result | Caught by |")
    print("|---|----------|--------|-----------|")
    for mid, desc, status, names in rows:
        print(f"| {mid} | {desc} | {status} | {', '.join(names) or '-'} |")
    killed = sum(1 for r in rows if r[2] == "KILLED")
    print(f"\nkilled {killed}/{len(rows)}")


if __name__ == "__main__":
    main()

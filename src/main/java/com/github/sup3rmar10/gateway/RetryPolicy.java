package com.github.sup3rmar10.gateway;

import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Which requests may be retried, and how long to wait between attempts (exponential, full jitter). */
public final class RetryPolicy {
    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "PUT", "DELETE");

    private final long baseMillis;
    private final long maxMillis;
    private final Random rng;

    public RetryPolicy(long baseMillis, long maxMillis, Random rng) {
        if (baseMillis < 0 || maxMillis < baseMillis) {
            throw new IllegalArgumentException("need 0 <= base <= max");
        }
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.rng = rng;
    }

    /** GET/HEAD/PUT/DELETE are retryable; anything else only with a non-blank Idempotency-Key. */
    public static boolean isRetryable(String method, String idempotencyKey) {
        if (IDEMPOTENT.contains(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    /** Upper bound of the backoff before retry number {@code attempt} (1 = wait after the first failure). */
    public long capFor(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        return Math.min(maxMillis, baseMillis * (1L << shift));
    }

    /** Full jitter: uniform in [0, capFor(attempt)]. */
    public long backoffMillis(int attempt) {
        long cap = capFor(attempt);
        synchronized (rng) {
            return (long) (rng.nextDouble() * (cap + 1));
        }
    }
}

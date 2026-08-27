package io.github.mehmetztrk.llmgateway.domain.limits;

import java.time.Duration;

/**
 * The state of one bucket after a decision, in the shape the response headers need.
 *
 * <p>why carry this out of the limiter rather than re-reading it later: a second read would be a
 * second round trip and would report a different moment than the one the decision was made in.
 * Clients use these headers to pace themselves, so they have to describe the decision that actually
 * happened.
 *
 * @param limit the configured ceiling, echoed back so a client can see what it is being held to
 * @param remaining tokens left in the bucket after this decision, never negative
 * @param retryAfter how long until the requested amount would be available; {@link Duration#ZERO}
 *     when the request was allowed
 */
public record RateLimitSnapshot(long limit, long remaining, Duration retryAfter, boolean allowed) {

    public RateLimitSnapshot {
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining must not be negative");
        }
    }

    public static RateLimitSnapshot allowed(long limit, long remaining) {
        return new RateLimitSnapshot(limit, remaining, Duration.ZERO, true);
    }

    public static RateLimitSnapshot denied(long limit, long remaining, Duration retryAfter) {
        return new RateLimitSnapshot(limit, remaining, retryAfter, false);
    }

    /** Seconds a client should wait, rounded up — rounding down would invite an immediate retry. */
    public long retryAfterSeconds() {
        return retryAfter.isZero() ? 0 : Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}

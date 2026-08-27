package io.github.mehmetztrk.llmgateway.domain.error;

import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;

/**
 * The caller is going too fast. Maps to HTTP 429 with OpenAI's {@code rate_limit_exceeded} code.
 *
 * <p>Carries the snapshot that produced the refusal so the response can tell the client exactly how
 * long to wait. A 429 without {@code Retry-After} is an invitation to hammer.
 *
 * @param scope which bucket ran out — the tenant's or the individual key's. The client is told
 *     which, because "your key is throttled" and "your organisation is throttled" call for
 *     completely different actions.
 */
public final class RateLimitExceeded extends GatewayException {

    public enum Scope {
        TENANT_REQUESTS,
        TENANT_TOKENS,
        KEY_REQUESTS,
        KEY_TOKENS
    }

    private final Scope scope;
    private final transient RateLimitSnapshot snapshot;

    public RateLimitExceeded(Scope scope, RateLimitSnapshot snapshot) {
        super("Rate limit exceeded on " + scope + "; retry after " + snapshot.retryAfterSeconds() + "s");
        this.scope = scope;
        this.snapshot = snapshot;
    }

    public Scope scope() {
        return scope;
    }

    public RateLimitSnapshot snapshot() {
        return snapshot;
    }
}

package io.github.mehmetztrk.llmgateway.domain.error;

import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;

/**
 * An upstream provider failed: connection refused, timeout, malformed response, or an error status.
 * Maps to HTTP 502 — the gateway itself is healthy, the thing behind it is not, and a client
 * retrying against a different model may well succeed.
 *
 * <p>From M5 this is the signal the circuit breaker and failover logic act on.
 */
public final class ProviderCallFailed extends GatewayException {

    private final ProviderId provider;

    public ProviderCallFailed(ProviderId provider, String reason, Throwable cause) {
        super("Provider '" + provider + "' failed: " + reason, cause);
        this.provider = provider;
    }

    public ProviderCallFailed(ProviderId provider, String reason) {
        super("Provider '" + provider + "' failed: " + reason);
        this.provider = provider;
    }

    public ProviderId provider() {
        return provider;
    }
}

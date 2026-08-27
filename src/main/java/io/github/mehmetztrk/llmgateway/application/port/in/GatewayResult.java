package io.github.mehmetztrk.llmgateway.application.port.in;

import io.github.mehmetztrk.llmgateway.domain.limits.QuotaSnapshot;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;

/**
 * A response together with the limit state that admitted it.
 *
 * <p>why wrap rather than return the body alone: the rate-limit headers a client uses to pace
 * itself must describe the decision that actually happened. Reading the buckets again in the web
 * layer would be a second round trip reporting a different moment, and would tempt someone to skip
 * it entirely on the streaming path — exactly where a client most needs to know its budget.
 *
 * @param body the completion, or the stream of chunks
 */
public record GatewayResult<T>(T body, RateLimitSnapshot requests, RateLimitSnapshot tokens, QuotaSnapshot quota) {

    public <R> GatewayResult<R> withBody(R newBody) {
        return new GatewayResult<>(newBody, requests, tokens, quota);
    }
}

package io.github.mehmetztrk.llmgateway.domain.error;

/**
 * The rate limiter could not reach its backing store. Maps to HTTP 503.
 *
 * <p><b>This exists so that the gateway fails closed.</b> When Redis is unreachable there is no way
 * to know whether a request is within its limit, and the two options are to let it through or to
 * refuse it. Letting it through means an outage of the limiter silently becomes unlimited access to
 * every provider behind the gateway — which is precisely the situation limits exist to prevent, and
 * one that costs real money the moment a paid provider is configured. See ADR-0004.
 *
 * <p>The cache makes the opposite choice, for the opposite reason.
 */
public final class LimiterUnavailable extends GatewayException {

    public LimiterUnavailable(String detail, Throwable cause) {
        super("Rate limiting is unavailable: " + detail, cause);
    }
}

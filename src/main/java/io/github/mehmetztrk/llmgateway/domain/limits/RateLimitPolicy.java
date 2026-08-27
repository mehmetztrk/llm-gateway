package io.github.mehmetztrk.llmgateway.domain.limits;

/**
 * How fast a caller may go.
 *
 * <p>Two independent dimensions, because they constrain different things. Requests per minute bound
 * how often someone may ask; tokens per minute bound how much work they may cause. A caller sending
 * one enormous prompt a second is cheap by the first measure and ruinous by the second, and a
 * gateway that only counted requests would happily let it through.
 *
 * @param requestsPerMinute bucket capacity and refill rate for request count
 * @param tokensPerMinute bucket capacity and refill rate for total tokens
 */
public record RateLimitPolicy(int requestsPerMinute, long tokensPerMinute) {

    public RateLimitPolicy {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException("requestsPerMinute must be positive");
        }
        if (tokensPerMinute <= 0) {
            throw new IllegalArgumentException("tokensPerMinute must be positive");
        }
    }

    /** Picks the tighter of two policies, dimension by dimension. */
    public RateLimitPolicy strictest(RateLimitPolicy other) {
        return new RateLimitPolicy(
                Math.min(requestsPerMinute, other.requestsPerMinute), Math.min(tokensPerMinute, other.tokensPerMinute));
    }
}

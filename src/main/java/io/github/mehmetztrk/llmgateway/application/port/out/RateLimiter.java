package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;
import reactor.core.publisher.Mono;

/**
 * A token bucket, wherever it happens to live.
 *
 * <p>Reactive, unlike {@link TenantRepository}: the Redis driver is non-blocking, so there is no
 * boundary to cross and no scheduler hop to pay. That asymmetry between the two ports is
 * deliberate — each one is honest about the thing behind it.
 *
 * <p>Implementations must be <b>atomic</b>. Read-then-write across two round trips lets two
 * concurrent requests both observe capacity and both consume it, which turns a limit of N into a
 * limit of N plus however many callers raced.
 */
public interface RateLimiter {

    /**
     * Take {@code permits} from a bucket, or refuse.
     *
     * @param bucketKey identifies the bucket; the caller is responsible for scoping it so that one
     *     tenant can never consume another's capacity
     * @param permits how much to take. Requests use 1; token buckets use an estimate up front and
     *     settle the difference afterwards with {@link #settle}.
     * @return the resulting state, allowed or not. Failure to reach the store is signalled as
     *     {@link io.github.mehmetztrk.llmgateway.domain.error.LimiterUnavailable}, never as a
     *     silent allow.
     */
    Mono<RateLimitSnapshot> tryConsume(String bucketKey, long limit, long permits);

    /**
     * Charge the difference between what was estimated and what was actually used.
     *
     * <p>Deliberately cannot refuse: the work is already done and the tokens already spent. It may
     * push the bucket to empty, which delays the <em>next</em> request rather than retroactively
     * failing this one.
     */
    Mono<Void> settle(String bucketKey, long limit, long additionalPermits);
}

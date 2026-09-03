package io.github.mehmetztrk.llmgateway.limits;

import io.github.mehmetztrk.llmgateway.application.port.out.RateLimiter;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * A rate limiter with no Redis and no refill, for tests about something else.
 *
 * <p>why a hand-written double rather than a mock: it has real behaviour — buckets that actually
 * drain — so a test can exhaust one without stubbing a specific call sequence. There is no refill
 * because no test needs one; the refill arithmetic is Lua, and it is tested against a real Redis.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, AtomicLong> buckets = new ConcurrentHashMap<>();

    /**
     * why synchronized rather than a clever {@code updateAndGet}: the decision and the deduction
     * have to be one step, and the caller needs to know which happened. Deriving "was it allowed"
     * from the remaining count afterwards is exactly the bug this double existed to help find —
     * an empty bucket and a bucket with room can leave the same remaining value.
     */
    @Override
    public synchronized Mono<RateLimitSnapshot> tryConsume(String bucketKey, long limit, long permits) {
        AtomicLong bucket = buckets.computeIfAbsent(bucketKey, key -> new AtomicLong(limit));
        long current = bucket.get();

        if (current < permits) {
            return Mono.just(RateLimitSnapshot.denied(limit, current, Duration.ofSeconds(1)));
        }
        long remaining = current - permits;
        bucket.set(remaining);
        return Mono.just(RateLimitSnapshot.allowed(limit, remaining));
    }

    @Override
    public Mono<Void> settle(String bucketKey, long limit, long additionalPermits) {
        buckets.computeIfAbsent(bucketKey, key -> new AtomicLong(limit))
                .updateAndGet(current -> Math.max(0, current - additionalPermits));
        return Mono.empty();
    }

    /** Drains a bucket so a test can assert what happens at the limit. */
    public void exhaust(String bucketKey) {
        buckets.put(bucketKey, new AtomicLong(0));
    }
}

package io.github.mehmetztrk.llmgateway.adapter.out.redis;

import io.github.mehmetztrk.llmgateway.application.port.out.RateLimiter;
import io.github.mehmetztrk.llmgateway.domain.error.LimiterUnavailable;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * Token bucket backed by Redis, evaluated entirely inside one Lua script.
 *
 * <p>One round trip per bucket per request, and the read-modify-write is atomic. See
 * {@code redis/token_bucket.lua} for why both of those matter.
 *
 * <p><b>Fails closed.</b> Any error reaching Redis becomes {@link LimiterUnavailable}, never a
 * silent allow. See ADR-0004.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private static final String KEY_PREFIX = "llmgw:rl:";

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> script;
    private final Clock clock;
    private final Duration timeout;

    public RedisTokenBucketRateLimiter(
            ReactiveStringRedisTemplate redis, RedisScript<List> script, Clock clock, Duration timeout) {
        this.redis = redis;
        this.script = script;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public Mono<RateLimitSnapshot> tryConsume(String bucketKey, long limit, long permits) {
        return run(bucketKey, limit, permits, false).map(result -> {
            long remaining = result.get(1);
            return result.get(0) == 1L
                    ? RateLimitSnapshot.allowed(limit, remaining)
                    : RateLimitSnapshot.denied(limit, remaining, Duration.ofMillis(result.get(2)));
        });
    }

    @Override
    public Mono<Void> settle(String bucketKey, long limit, long additionalPermits) {
        if (additionalPermits <= 0) {
            return Mono.empty();
        }
        return run(bucketKey, limit, additionalPermits, true).then();
    }

    private Mono<List<Long>> run(String bucketKey, long limit, long permits, boolean force) {
        // Refill over a one-minute window: the policy is expressed per minute, the bucket in
        // tokens per second, so a limit of 60/min refills at exactly 1/s and a burst of 60 is
        // allowed at once. That burst tolerance is the property a token bucket has and a fixed
        // window does not — see ADR-0003.
        double refillPerSecond = limit / 60.0;

        List<String> keys = List.of(KEY_PREFIX + bucketKey);
        List<String> args = List.of(
                Long.toString(limit),
                Double.toString(refillPerSecond),
                Long.toString(clock.millis()),
                Long.toString(permits),
                force ? "1" : "0");

        return redis.execute(script, keys, args)
                .next()
                .map(raw -> ((List<?>) raw)
                        .stream().map(value -> ((Number) value).longValue()).toList())
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof LimiterUnavailable), error -> {
                    // Logged at warn, not error: Redis being briefly unreachable is an operational
                    // event the operator must see, but it is the client that gets the 503 and the
                    // gateway itself is behaving exactly as designed.
                    log.warn("rate limiter unavailable for bucket {}: {}", bucketKey, error.toString());
                    return new LimiterUnavailable("could not reach Redis", error);
                });
    }
}

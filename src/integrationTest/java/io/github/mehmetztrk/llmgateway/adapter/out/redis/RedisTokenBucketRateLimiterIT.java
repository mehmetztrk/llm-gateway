package io.github.mehmetztrk.llmgateway.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import io.github.mehmetztrk.llmgateway.application.port.out.RateLimiter;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

/**
 * The bucket arithmetic, against a real Redis running the real Lua.
 *
 * <p>These assertions are the ones a hand-written in-memory double cannot make: atomicity under
 * concurrency, and refill driven by elapsed time inside the script.
 */
class RedisTokenBucketRateLimiterIT extends AbstractGatewayIT {

    @Autowired
    private RateLimiter limiter;

    private String freshBucket() {
        return "it:" + UUID.randomUUID();
    }

    @Test
    @DisplayName("a new bucket starts full and drains one permit at a time")
    void drainsAsItIsConsumed() {
        String bucket = freshBucket();

        RateLimitSnapshot first = limiter.tryConsume(bucket, 10, 1).block();
        RateLimitSnapshot second = limiter.tryConsume(bucket, 10, 1).block();

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(9);
        assertThat(second.remaining()).isEqualTo(8);
    }

    @Test
    @DisplayName("an exhausted bucket refuses and reports how long to wait")
    void refusesWhenEmpty() {
        String bucket = freshBucket();
        // A limit of 60/min refills at exactly 1/s, so the wait after emptying it is predictable.
        limiter.tryConsume(bucket, 60, 60).block();

        RateLimitSnapshot denied = limiter.tryConsume(bucket, 60, 1).block();

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 2L);
    }

    @Test
    @DisplayName("a request larger than the whole bucket is refused, not partially served")
    void refusesOversizedRequests() {
        RateLimitSnapshot denied = limiter.tryConsume(freshBucket(), 10, 25).block();

        assertThat(denied.allowed()).isFalse();
        // The bucket is untouched: refusing must not also consume, or a client retrying an
        // oversized request would drain capacity it never got to use.
        assertThat(denied.remaining()).isEqualTo(10);
    }

    @Test
    @DisplayName("concurrent consumers cannot together exceed the limit")
    void isAtomicUnderConcurrency() {
        // The reason the whole decision lives in one Lua script. With a read-then-write across two
        // round trips, several of these would observe the same remaining count and all proceed.
        String bucket = freshBucket();
        int limit = 50;
        AtomicLong allowed = new AtomicLong();

        Flux.range(0, 200)
                .flatMap(i -> limiter.tryConsume(bucket, limit, 1), 32)
                .doOnNext(snapshot -> {
                    if (snapshot.allowed()) {
                        allowed.incrementAndGet();
                    }
                })
                .blockLast(Duration.ofSeconds(30));

        // Refill during the run can legitimately allow a few extra, but never 200.
        assertThat(allowed.get()).isBetween((long) limit, (long) limit + 5);
    }

    @Test
    @DisplayName("settlement may overdraw, which delays the next request instead of failing this one")
    void settlementCanOverdraw() {
        String bucket = freshBucket();
        limiter.tryConsume(bucket, 100, 10).block();

        // The response turned out far larger than the estimate.
        limiter.settle(bucket, 100, 500).block();

        RateLimitSnapshot after = limiter.tryConsume(bucket, 100, 1).block();
        assertThat(after.allowed()).isFalse();
    }

    @Test
    @DisplayName("buckets are isolated from one another")
    void bucketsAreIndependent() {
        String a = freshBucket();
        String b = freshBucket();

        limiter.tryConsume(a, 5, 5).block();

        assertThat(limiter.tryConsume(a, 5, 1).block().allowed()).isFalse();
        assertThat(limiter.tryConsume(b, 5, 1).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("settling nothing costs no round trip")
    void settlingZeroIsANoOp() {
        String bucket = freshBucket();
        limiter.tryConsume(bucket, 10, 1).block();

        List.of(0L, -5L).forEach(amount -> limiter.settle(bucket, 10, amount).block());

        assertThat(limiter.tryConsume(bucket, 10, 1).block().remaining()).isEqualTo(8);
    }
}

package io.github.mehmetztrk.llmgateway.adapter.out.redis;

import io.github.mehmetztrk.llmgateway.application.port.out.QuotaStore;
import io.github.mehmetztrk.llmgateway.domain.error.LimiterUnavailable;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaSnapshot;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Monthly token counter in Redis.
 *
 * <p>The key embeds the calendar month in UTC, so the counter resets by expiring rather than by a
 * scheduled job that has to run on exactly one node at exactly midnight. A month nobody used costs
 * nothing, because its key never existed.
 *
 * <p>why UTC rather than a tenant-local month: billing periods that shift with a timezone are a
 * source of off-by-one arguments nobody wins. One boundary, documented, applied to everyone.
 */
public class RedisQuotaStore implements QuotaStore {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaStore.class);

    private static final String KEY_PREFIX = "llmgw:quota:";

    /** Long enough to survive the month plus slack for clock skew and late settlement. */
    private static final Duration KEY_TTL = Duration.ofDays(40);

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final Duration timeout;

    public RedisQuotaStore(ReactiveStringRedisTemplate redis, Clock clock, Duration timeout) {
        this.redis = redis;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public Mono<QuotaSnapshot> current(TenantId tenantId, QuotaPolicy policy) {
        if (policy.isUnlimited()) {
            // No budget means no reason to touch Redis at all. Skipping the round trip is not a
            // micro-optimisation here: it keeps unlimited tenants working during a Redis outage.
            return Mono.just(QuotaSnapshot.UNLIMITED);
        }
        return redis.opsForValue()
                .get(key(tenantId))
                .map(Long::parseLong)
                .defaultIfEmpty(0L)
                .map(used -> QuotaSnapshot.evaluate(policy, used))
                .timeout(timeout)
                .onErrorMap(this::unavailable);
    }

    @Override
    public Mono<QuotaSnapshot> record(TenantId tenantId, QuotaPolicy policy, long tokens) {
        if (policy.isUnlimited() || tokens <= 0) {
            return Mono.just(QuotaSnapshot.UNLIMITED);
        }
        String key = key(tenantId);
        return redis.opsForValue()
                .increment(key, tokens)
                // The TTL is refreshed on every write rather than set once on creation: a SET that
                // races with an EXPIRE can otherwise leave a key with no expiry at all, and under
                // volatile-lru an immortal key is one Redis will never evict.
                .flatMap(used -> redis.expire(key, KEY_TTL).thenReturn(used))
                .map(used -> QuotaSnapshot.evaluate(policy, used))
                .timeout(timeout)
                .onErrorMap(this::unavailable);
    }

    private String key(TenantId tenantId) {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return KEY_PREFIX + tenantId.value() + ":" + month;
    }

    private Throwable unavailable(Throwable error) {
        if (error instanceof LimiterUnavailable) {
            return error;
        }
        log.warn("quota store unavailable: {}", error.toString());
        return new LimiterUnavailable("could not reach Redis for quota accounting", error);
    }
}

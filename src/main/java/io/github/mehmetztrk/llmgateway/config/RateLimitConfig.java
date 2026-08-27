package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.adapter.out.redis.RedisQuotaStore;
import io.github.mehmetztrk.llmgateway.adapter.out.redis.RedisTokenBucketRateLimiter;
import io.github.mehmetztrk.llmgateway.application.port.out.QuotaStore;
import io.github.mehmetztrk.llmgateway.application.port.out.RateLimiter;
import io.github.mehmetztrk.llmgateway.application.service.RateLimitService;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** Wires the Redis-backed limiter and quota counter. */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /**
     * why load the Lua from a resource rather than embed it in a Java string: it stays syntax
     * highlighted, diffable and commentable, and Spring computes its SHA-1 once so Redis executes
     * it by digest with {@code EVALSHA} instead of shipping the source on every call.
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> tokenBucketScript() {
        return RedisScript.of(new ClassPathResource("redis/token_bucket.lua"), List.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RateLimiter rateLimiter(
            ReactiveStringRedisTemplate redis,
            RedisScript<List> tokenBucketScript,
            Clock clock,
            RateLimitProperties properties) {
        return new RedisTokenBucketRateLimiter(redis, tokenBucketScript, clock, properties.timeout());
    }

    @Bean
    public QuotaStore quotaStore(ReactiveStringRedisTemplate redis, Clock clock, RateLimitProperties properties) {
        return new RedisQuotaStore(redis, clock, properties.timeout());
    }

    @Bean
    public RateLimitService rateLimitService(RateLimiter rateLimiter, QuotaStore quotaStore) {
        return new RateLimitService(rateLimiter, quotaStore);
    }
}

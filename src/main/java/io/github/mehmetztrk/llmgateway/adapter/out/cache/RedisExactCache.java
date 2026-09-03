package io.github.mehmetztrk.llmgateway.adapter.out.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.application.port.out.ResponseCache;
import io.github.mehmetztrk.llmgateway.domain.cache.CacheKey;
import io.github.mehmetztrk.llmgateway.domain.cache.CachedCompletion;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.Role;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Exact-match cache in Redis: same tenant, same model, same prompt, same parameters.
 *
 * <p>Tried before the semantic cache because it is both cheaper — one key lookup against a vector
 * scan — and unconditionally correct. A byte-identical prompt cannot be the wrong answer, so there
 * is no threshold to tune and nothing to get subtly wrong.
 *
 * <p><b>Fails open.</b> Every error becomes a miss. See ADR-0004 for why the cache makes the
 * opposite choice to the rate limiter.
 */
public class RedisExactCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisExactCache.class);
    private static final String KEY_PREFIX = "llmgw:cache:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Duration timeout;

    public RedisExactCache(
            ReactiveStringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl, Duration timeout) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.timeout = timeout;
    }

    @Override
    public Mono<CachedCompletion> lookup(TenantId tenant, ChatRequest request) {
        String key = KEY_PREFIX + CacheKey.of(tenant, request);
        return redis.opsForValue()
                .get(key)
                .timeout(timeout)
                .mapNotNull(json -> deserialise(json, request))
                .map(CachedCompletion::exact)
                .onErrorResume(error -> {
                    log.debug("exact cache unavailable, treating as a miss: {}", error.toString());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> store(TenantId tenant, ChatRequest request, Completion completion) {
        String key = KEY_PREFIX + CacheKey.of(tenant, request);
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(new Stored(
                        completion.id(),
                        completion.model(),
                        completion.servedBy().value(),
                        completion.message().content(),
                        completion.usage().promptTokens(),
                        completion.usage().completionTokens(),
                        completion.finishReason().name(),
                        completion.createdAt().toEpochMilli())))
                .flatMap(json -> redis.opsForValue().set(key, json, ttl))
                .timeout(timeout)
                .onErrorResume(error -> {
                    log.debug("could not write to the exact cache: {}", error.toString());
                    return Mono.empty();
                })
                .then();
    }

    private Completion deserialise(String json, ChatRequest request) {
        try {
            Stored stored = objectMapper.readValue(json, Stored.class);
            return new Completion(
                    stored.id(),
                    stored.model(),
                    ProviderId.of(stored.servedBy()),
                    new ChatMessage(Role.ASSISTANT, stored.content()),
                    new TokenUsage(stored.promptTokens(), stored.completionTokens()),
                    FinishReason.valueOf(stored.finishReason()),
                    Instant.ofEpochMilli(stored.createdAtMillis()));
        } catch (Exception e) {
            // A stored entry we can no longer read — after a schema change, say — is a miss, not
            // a failure. The alternative is an outage triggered by a deploy.
            log.warn("discarding unreadable cache entry for model {}", request.model());
            return null;
        }
    }

    /**
     * A flat, explicit shape rather than serialising the domain record directly. Jackson-ing a
     * domain type into a cache means the cache silently breaks the day someone adds a field to it,
     * and the breakage shows up as stale entries deserialising into the wrong thing.
     */
    private record Stored(
            String id,
            String model,
            String servedBy,
            String content,
            int promptTokens,
            int completionTokens,
            String finishReason,
            long createdAtMillis) {}
}

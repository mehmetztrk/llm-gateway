package io.github.mehmetztrk.llmgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.adapter.out.cache.PgVectorSemanticCache;
import io.github.mehmetztrk.llmgateway.adapter.out.cache.RedisExactCache;
import io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama.OllamaEmbeddingProvider;
import io.github.mehmetztrk.llmgateway.application.port.out.EmbeddingProvider;
import io.github.mehmetztrk.llmgateway.application.port.out.ResponseCache;
import io.github.mehmetztrk.llmgateway.application.service.CacheService;
import io.github.mehmetztrk.llmgateway.domain.cache.CachedCompletion;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Wires the two cache layers. */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    /**
     * A cache that never hits and never stores.
     *
     * <p>why a null object rather than {@code @ConditionalOnProperty} on the real beans: the
     * pipeline then has no branch for "caching is off", so the disabled path exercises exactly the
     * same code as the enabled one. A conditional bean would leave a code path that only runs in
     * configurations nobody tests.
     */
    private static final ResponseCache DISABLED = new ResponseCache() {
        @Override
        public Mono<CachedCompletion> lookup(TenantId tenant, ChatRequest request) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> store(TenantId tenant, ChatRequest request, Completion completion) {
            return Mono.empty();
        }
    };

    @Bean
    public EmbeddingProvider embeddingProvider(
            WebClient.Builder webClientBuilder, ProvidersProperties providers, CacheProperties cache) {
        // Embeddings come from the first configured Ollama instance: it is the one with the GPU,
        // and nomic-embed-text is pulled into it by scripts/pull-models.sh.
        String baseUrl = providers.ollama().values().stream()
                .filter(ProvidersProperties.OllamaInstance::enabled)
                .map(ProvidersProperties.OllamaInstance::baseUrl)
                .findFirst()
                .orElse("http://localhost:11434");

        return new OllamaEmbeddingProvider(
                webClientBuilder.baseUrl(baseUrl).build(),
                cache.embeddingModel(),
                cache.embeddingDimensions(),
                cache.embeddingTimeout());
    }

    @Bean
    public CacheService cacheService(
            ReactiveStringRedisTemplate redis,
            ObjectMapper objectMapper,
            JdbcClient jdbcClient,
            EmbeddingProvider embeddings,
            Scheduler blockingScheduler,
            CacheProperties properties) {

        if (!properties.enabled()) {
            return new CacheService(DISABLED, DISABLED, false);
        }

        ResponseCache exact = new RedisExactCache(redis, objectMapper, properties.ttl(), properties.redisTimeout());
        ResponseCache semantic = new PgVectorSemanticCache(
                jdbcClient, embeddings, blockingScheduler, properties.similarityThreshold(), properties.ttl());

        return new CacheService(exact, semantic, properties.semanticEnabled());
    }
}

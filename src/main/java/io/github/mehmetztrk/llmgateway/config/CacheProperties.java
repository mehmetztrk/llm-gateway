package io.github.mehmetztrk.llmgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code gateway.cache}.
 *
 * @param semanticEnabled the semantic layer needs an embedding model; the exact layer needs
 *     nothing. Separate switches so a deployment without embeddings still gets the cheap half.
 * @param similarityThreshold cosine similarity above which a different prompt is considered the
 *     same question. See ADR-0008 — this is the single most consequential number in the project,
 *     because too low means confidently wrong answers.
 * @param ttl how long an entry stays valid. Bounded because a model upgrade or a changed system
 *     prompt should not be served stale answers forever.
 * @param embeddingModel must match the {@code vector(n)} width in the schema.
 */
@ConfigurationProperties(prefix = "gateway.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean semanticEnabled,
        @DefaultValue("0.95") double similarityThreshold,
        @DefaultValue("1h") Duration ttl,
        @DefaultValue("nomic-embed-text") String embeddingModel,
        @DefaultValue("768") int embeddingDimensions,
        @DefaultValue("2s") Duration embeddingTimeout,
        @DefaultValue("250ms") Duration redisTimeout) {

    public CacheProperties {
        if (similarityThreshold <= 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("similarityThreshold must be within (0, 1]");
        }
        if (embeddingDimensions <= 0) {
            throw new IllegalArgumentException("embeddingDimensions must be positive");
        }
    }
}

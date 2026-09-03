package io.github.mehmetztrk.llmgateway.application.port.out;

import reactor.core.publisher.Mono;

/**
 * Turns text into a vector, for the semantic cache.
 *
 * <p>A port of its own rather than a method on {@link LlmProvider}: embedding and completion are
 * different capabilities with different models, and a provider may offer one without the other.
 *
 * <p>Emits empty rather than an error when embedding is unavailable, so the semantic cache degrades
 * to "miss" instead of failing the request. A cache is not worth an outage.
 */
public interface EmbeddingProvider {

    /** @return the embedding, or empty if it could not be produced */
    Mono<float[]> embed(String text);

    /** Dimensionality, which must match the {@code vector(n)} column in the schema. */
    int dimensions();
}

package io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.mehmetztrk.llmgateway.application.port.out.EmbeddingProvider;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Embeddings from Ollama's OpenAI-compatible {@code /v1/embeddings} endpoint.
 *
 * <p>Runs locally on {@code nomic-embed-text}, so the semantic cache costs no money and no external
 * dependency — which is the only reason a semantic cache is affordable under this project's
 * zero-budget rule at all.
 *
 * <p>Emits empty rather than an error when embedding fails: the semantic cache then degrades to a
 * miss, and the request is served normally. A cache is not worth an outage.
 */
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);

    private final WebClient webClient;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    public OllamaEmbeddingProvider(WebClient webClient, String model, int dimensions, Duration timeout) {
        this.webClient = webClient;
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
    }

    @Override
    public Mono<float[]> embed(String text) {
        return webClient
                .post()
                .uri("/v1/embeddings")
                .bodyValue(new EmbeddingRequest(model, text))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .timeout(timeout)
                .mapNotNull(response -> {
                    if (response.data() == null || response.data().isEmpty()) {
                        return null;
                    }
                    List<Double> values = response.data().getFirst().embedding();
                    if (values == null || values.size() != dimensions) {
                        // A vector of the wrong width would be rejected by Postgres anyway; failing
                        // here says why, once, instead of once per insert.
                        log.warn(
                                "embedding model {} returned {} dimensions, schema expects {}",
                                model,
                                values == null ? 0 : values.size(),
                                dimensions);
                        return null;
                    }
                    float[] vector = new float[values.size()];
                    for (int i = 0; i < values.size(); i++) {
                        vector[i] = values.get(i).floatValue();
                    }
                    return vector;
                })
                .onErrorResume(error -> {
                    log.debug("embedding unavailable, semantic cache will miss: {}", error.toString());
                    return Mono.empty();
                });
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private record EmbeddingRequest(String model, String input) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<EmbeddingData> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(List<Double> embedding) {}
}

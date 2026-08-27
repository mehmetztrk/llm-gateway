package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.adapter.out.provider.mock.MockProviderProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code gateway.providers}.
 *
 * <p>why Ollama instances live in a Map keyed by name rather than as fixed {@code primary} /
 * {@code secondary} fields: adding a third instance must not require a code change. The map key
 * becomes the {@link io.github.mehmetztrk.llmgateway.domain.routing.ProviderId}, which is also what
 * shows up in metrics and ledger rows — so the operator names the provider, not the developer.
 */
@ConfigurationProperties(prefix = "gateway.providers")
public record ProvidersProperties(
        @DefaultValue Map<String, OllamaInstance> ollama,
        @DefaultValue MockProviderProperties mock) {

    public ProvidersProperties {
        ollama = ollama == null ? Map.of() : new LinkedHashMap<>(ollama);
    }

    /**
     * @param baseUrl root URL of an Ollama server, without the {@code /v1} suffix
     * @param models model names this instance serves. There is no auto-discovery on purpose: the
     *     gateway must know its routing table at startup, not learn it from an upstream that may be
     *     down at the moment a request arrives.
     * @param timeout upper bound on a single completion call
     */
    public record OllamaInstance(
            @DefaultValue("true") boolean enabled,
            String baseUrl,
            @DefaultValue Set<String> models,
            @DefaultValue("60s") Duration timeout) {

        public OllamaInstance {
            models = models == null ? Set.of() : Set.copyOf(models);
        }
    }
}

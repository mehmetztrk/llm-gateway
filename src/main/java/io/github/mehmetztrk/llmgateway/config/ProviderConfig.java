package io.github.mehmetztrk.llmgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.adapter.out.provider.mock.MockProvider;
import io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama.OllamaProvider;
import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.application.service.ProviderRegistry;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Turns configuration into providers, and hands them to the registry.
 *
 * <p>why the providers are built here instead of being {@code @Component}s: the number of Ollama
 * instances is a configuration decision, so there is no fixed set of classes to annotate. Building
 * them in one place also keeps the adapters free of Spring wiring — an {@link OllamaProvider} is a
 * plain object you can construct in a test with four arguments.
 *
 * <p>why the provider list is not itself a bean: Spring resolves a {@code List<LlmProvider>}
 * injection point by collecting every bean <em>of type</em> {@code LlmProvider}, which means a bean
 * whose own type is {@code List<LlmProvider>} is quietly ignored and the injection point comes back
 * empty. Building the list locally and publishing only the registry sidesteps that entirely.
 */
@Configuration(proxyBeanMethods = false)
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

    @Bean
    public ProviderRegistry providerRegistry(
            ProvidersProperties properties,
            WebClient.Builder webClientBuilder,
            Clock clock,
            ObjectMapper objectMapper) {
        return new ProviderRegistry(buildProviders(properties, webClientBuilder, clock, objectMapper));
    }

    private List<LlmProvider> buildProviders(
            ProvidersProperties properties,
            WebClient.Builder webClientBuilder,
            Clock clock,
            ObjectMapper objectMapper) {

        List<LlmProvider> providers = new ArrayList<>();

        for (Map.Entry<String, ProvidersProperties.OllamaInstance> entry :
                properties.ollama().entrySet()) {
            ProvidersProperties.OllamaInstance instance = entry.getValue();
            if (!instance.enabled()) {
                log.info("provider {} is disabled by configuration", entry.getKey());
                continue;
            }
            if (instance.baseUrl() == null || instance.baseUrl().isBlank()) {
                throw new IllegalStateException(
                        "gateway.providers.ollama." + entry.getKey() + ".base-url must be set when enabled");
            }
            ProviderId id = ProviderId.of(entry.getKey());
            providers.add(new OllamaProvider(
                    id,
                    webClientBuilder.baseUrl(instance.baseUrl()).build(),
                    instance.models(),
                    instance.timeout(),
                    objectMapper));
            log.info("registered provider {} at {} serving {}", id, instance.baseUrl(), instance.models());
        }

        if (properties.mock().enabled()) {
            ProviderId id = ProviderId.of("mock");
            providers.add(new MockProvider(id, properties.mock(), clock));
            log.info("registered provider {} serving {}", id, properties.mock().models());
        }

        if (providers.isEmpty()) {
            throw new IllegalStateException("no providers configured: the gateway would reject every request");
        }
        return List.copyOf(providers);
    }
}

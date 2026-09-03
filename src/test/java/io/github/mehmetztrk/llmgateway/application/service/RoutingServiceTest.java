package io.github.mehmetztrk.llmgateway.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.application.port.out.ProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotFound;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderHealth;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RoutingServiceTest {

    private record StubProvider(ProviderId id, Set<String> supportedModels) implements LlmProvider {
        @Override
        public Mono<Completion> complete(ChatRequest request) {
            return Mono.empty();
        }

        @Override
        public Flux<CompletionChunk> stream(ChatRequest request) {
            return Flux.empty();
        }

        @Override
        public Mono<Boolean> isHealthy() {
            return Mono.just(true);
        }
    }

    /** Health held in a plain map so a test can assert on ordering without timing anything. */
    private static final class FixedHealth implements ProviderHealthRegistry {
        private final Map<ProviderId, ProviderHealth> states = new HashMap<>();

        @Override
        public ProviderHealth healthOf(ProviderId provider) {
            return states.getOrDefault(provider, ProviderHealth.UNKNOWN);
        }

        @Override
        public Map<ProviderId, ProviderHealth> snapshot() {
            return Map.copyOf(states);
        }

        @Override
        public void recordSuccess(ProviderId provider) {
            states.put(provider, ProviderHealth.UP);
        }

        @Override
        public void recordFailure(ProviderId provider) {
            states.put(provider, ProviderHealth.DOWN);
        }
    }

    private final FixedHealth health = new FixedHealth();

    private RoutingService service(Map<String, List<RouteTarget>> aliases) {
        ProviderRegistry providers = new ProviderRegistry(List.of(
                new StubProvider(ProviderId.of("primary"), Set.of("real-model")),
                new StubProvider(ProviderId.of("secondary"), Set.of("other-model"))));
        return new RoutingService(providers, health, aliases);
    }

    @Test
    @DisplayName("an alias resolves to its configured targets, in order")
    void aliasResolvesInConfiguredOrder() {
        RoutingService routing = service(Map.of(
                "chat", List.of(RouteTarget.of("primary", "real-model"), RouteTarget.of("secondary", "other-model"))));

        assertThat(routing.resolve("chat"))
                .containsExactly(RouteTarget.of("primary", "real-model"), RouteTarget.of("secondary", "other-model"));
    }

    @Test
    @DisplayName("a concrete model name still resolves, so hardcoded clients keep working")
    void concreteModelStillResolves() {
        RoutingService routing = service(Map.of());

        assertThat(routing.resolve("real-model")).containsExactly(RouteTarget.of("primary", "real-model"));
    }

    @Test
    @DisplayName("a provider believed DOWN is moved to the back, not removed")
    void unhealthyProviderIsDeprioritised() {
        RoutingService routing = service(Map.of(
                "chat", List.of(RouteTarget.of("primary", "real-model"), RouteTarget.of("secondary", "other-model"))));

        health.recordFailure(ProviderId.of("primary"));
        health.recordSuccess(ProviderId.of("secondary"));

        assertThat(routing.resolve("chat"))
                .containsExactly(RouteTarget.of("secondary", "other-model"), RouteTarget.of("primary", "real-model"));
    }

    @Test
    @DisplayName("when every candidate looks down the request is still attempted")
    void allDownStillRoutes() {
        // A stale health belief must not become a self-inflicted outage. Ordering, not filtering.
        RoutingService routing = service(Map.of(
                "chat", List.of(RouteTarget.of("primary", "real-model"), RouteTarget.of("secondary", "other-model"))));

        health.recordFailure(ProviderId.of("primary"));
        health.recordFailure(ProviderId.of("secondary"));

        assertThat(routing.resolve("chat")).hasSize(2);
    }

    @Test
    @DisplayName("an alias entry naming a provider that is not configured is skipped, not fatal")
    void unknownProviderInAliasIsSkipped() {
        // One misconfigured entry must not take down a whole route.
        RoutingService routing = service(Map.of(
                "chat",
                List.of(RouteTarget.of("typo-provider", "real-model"), RouteTarget.of("primary", "real-model"))));

        assertThat(routing.resolve("chat")).containsExactly(RouteTarget.of("primary", "real-model"));
    }

    @Test
    @DisplayName("a name nothing serves is ModelNotFound, not an empty list")
    void unknownModelThrows() {
        RoutingService routing = service(Map.of());

        assertThatThrownBy(() -> routing.resolve("nobody-serves-this")).isInstanceOf(ModelNotFound.class);
    }

    @Test
    @DisplayName("UP sorts ahead of UNKNOWN, which sorts ahead of DOWN")
    void healthOrderingIsTotal() {
        assertThat(ProviderHealth.UP.preference()).isLessThan(ProviderHealth.UNKNOWN.preference());
        assertThat(ProviderHealth.UNKNOWN.preference()).isLessThan(ProviderHealth.DOWN.preference());
        assertThat(ProviderHealth.UNKNOWN.isRoutable()).isTrue();
        assertThat(ProviderHealth.DOWN.isRoutable()).isFalse();
    }
}

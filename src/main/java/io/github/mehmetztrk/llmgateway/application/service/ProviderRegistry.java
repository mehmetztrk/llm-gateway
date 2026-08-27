package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotFound;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a model name to the provider that serves it.
 *
 * <p>M1 keeps this deliberately dumb — first registered provider that claims the model wins. Health
 * checks, tenant policy and failover ordering arrive in M5; putting them here now would mean
 * writing routing logic with no way to test it.
 *
 * <p>Note there are no Spring annotations in this class or anywhere else in {@code application}.
 * Beans are declared in {@code config}, which keeps the use-case layer unit-testable with plain
 * constructors and enforceable by an ArchUnit rule.
 */
public class ProviderRegistry {

    private final List<LlmProvider> providers;
    private final Map<ProviderId, LlmProvider> byId;

    public ProviderRegistry(List<LlmProvider> providers) {
        this.providers = List.copyOf(providers);
        Map<ProviderId, LlmProvider> index = new LinkedHashMap<>();
        for (LlmProvider provider : this.providers) {
            LlmProvider previous = index.put(provider.id(), provider);
            if (previous != null) {
                throw new IllegalStateException("duplicate provider id: " + provider.id());
            }
        }
        this.byId = Map.copyOf(index);
    }

    public LlmProvider requireProviderFor(String model) {
        return findProviderFor(model).orElseThrow(() -> new ModelNotFound(model));
    }

    public Optional<LlmProvider> findProviderFor(String model) {
        return providers.stream().filter(p -> p.supports(model)).findFirst();
    }

    public Optional<LlmProvider> byId(ProviderId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<LlmProvider> all() {
        return providers;
    }
}

package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.out.ProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotFound;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderHealth;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns "the tenant asked for X" into an ordered list of places to try.
 *
 * <p><b>Aliases are what make failover expressible.</b> A tenant asking for {@code chat-default}
 * has said what it wants, not where to get it; the gateway decides that a GPU-backed
 * {@code qwen2.5:1.5b-instruct} is the first choice and a CPU-backed {@code llama3.2:1b} is the
 * fallback. Without that indirection, "fail over" would mean "silently answer with a different
 * model than the one the client named", which is not a decision a gateway gets to make on its own.
 *
 * <p>A request for a concrete model name still works and resolves to exactly one target. That keeps
 * the OpenAI compatibility promise intact: a client that hardcodes a model name is not forced to
 * learn about aliases.
 *
 * <p><b>Ordering, not filtering.</b> Providers believed DOWN are moved to the back rather than
 * removed. If every candidate looks down, the request is still attempted against the least-bad one
 * — a stale health belief must not turn into a self-inflicted outage.
 */
public class RoutingService {

    private final ProviderRegistry providers;
    private final ProviderHealthRegistry health;
    private final Map<String, List<RouteTarget>> aliases;

    public RoutingService(
            ProviderRegistry providers, ProviderHealthRegistry health, Map<String, List<RouteTarget>> aliases) {
        this.providers = providers;
        this.health = health;
        this.aliases = Map.copyOf(aliases);
    }

    /**
     * @return candidates in the order they should be tried, never empty
     * @throws ModelNotFound when nothing at all serves the requested name
     */
    public List<RouteTarget> resolve(String requestedModel) {
        List<RouteTarget> candidates = aliases.get(requestedModel);

        if (candidates == null) {
            // Not an alias: exactly one target, the first provider that declares this model.
            //
            // why not every provider that happens to serve the same name: the client named a
            // specific model, so silently spreading its traffic across two providers would make
            // identical requests return different answers depending on which one answered — and
            // it did, until this was fixed. Failover is what aliases are for; a concrete name is
            // a statement about *where*, not just *what*.
            candidates = providers.all().stream()
                    .filter(provider -> provider.supports(requestedModel))
                    .findFirst()
                    .map(provider -> List.of(new RouteTarget(provider.id(), requestedModel)))
                    .orElse(List.of());
        } else {
            // An alias may name a provider that is disabled in this environment; drop those rather
            // than failing the whole alias, so one misconfigured entry cannot take down a route.
            candidates = candidates.stream()
                    .filter(target -> providers.byId(target.provider()).isPresent())
                    .toList();
        }

        if (candidates.isEmpty()) {
            throw new ModelNotFound(requestedModel);
        }

        List<RouteTarget> ordered = new ArrayList<>(candidates);
        // A stable sort: within the same health state, the configured order is preserved, so an
        // operator's stated preference still decides between two healthy providers.
        ordered.sort(Comparator.comparingInt(target -> healthOf(target).preference()));
        return List.copyOf(ordered);
    }

    public ProviderHealth healthOf(RouteTarget target) {
        return health.healthOf(target.provider());
    }

    public Map<ProviderId, ProviderHealth> healthSnapshot() {
        return health.snapshot();
    }
}

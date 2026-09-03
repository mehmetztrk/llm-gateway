package io.github.mehmetztrk.llmgateway.health;

import io.github.mehmetztrk.llmgateway.application.port.out.ProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderHealth;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A health registry that remembers what it was told.
 *
 * <p>Recording the calls, rather than only the resulting state, lets a test assert that failover
 * reported the failure it acted on — which is the part that keeps routing decisions honest on the
 * next request.
 */
public class RecordingHealthRegistry implements ProviderHealthRegistry {

    private final Map<ProviderId, ProviderHealth> states = new ConcurrentHashMap<>();
    private final List<ProviderId> successes = new CopyOnWriteArrayList<>();
    private final List<ProviderId> failures = new CopyOnWriteArrayList<>();

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
        successes.add(provider);
    }

    @Override
    public void recordFailure(ProviderId provider) {
        states.put(provider, ProviderHealth.DOWN);
        failures.add(provider);
    }

    public List<ProviderId> successes() {
        return List.copyOf(successes);
    }

    public List<ProviderId> failures() {
        return List.copyOf(failures);
    }
}

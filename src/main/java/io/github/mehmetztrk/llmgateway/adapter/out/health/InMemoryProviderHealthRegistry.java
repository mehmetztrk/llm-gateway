package io.github.mehmetztrk.llmgateway.adapter.out.health;

import io.github.mehmetztrk.llmgateway.application.port.out.ProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderHealth;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health state held in memory, per instance.
 *
 * <p>why not shared in Redis: health is an observation, not a fact, and it is an observation about
 * <em>this instance's</em> ability to reach a provider. A replica in another availability zone may
 * legitimately see something different, and averaging those into one shared value produces a number
 * that is true for nobody. Sharing it would also put a network round trip on the routing path,
 * which is the one place that cannot afford one.
 *
 * <p>The cost is that each replica learns about an outage independently. With live traffic feeding
 * the registry, that takes a few requests rather than a few seconds.
 */
public class InMemoryProviderHealthRegistry implements ProviderHealthRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProviderHealthRegistry.class);

    private final Map<ProviderId, State> states = new ConcurrentHashMap<>();
    private final int failureThreshold;

    public InMemoryProviderHealthRegistry(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    @Override
    public ProviderHealth healthOf(ProviderId provider) {
        State state = states.get(provider);
        return state == null ? ProviderHealth.UNKNOWN : state.health.get();
    }

    @Override
    public Map<ProviderId, ProviderHealth> snapshot() {
        return states.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().health.get()));
    }

    @Override
    public void recordSuccess(ProviderId provider) {
        State state = states.computeIfAbsent(provider, id -> new State());
        state.consecutiveFailures.set(0);
        // Recovery is immediate on a single success, while failure needs several in a row. The
        // asymmetry is deliberate: being wrong about "down" costs a usable provider, being wrong
        // about "up" costs one failed request that then fails over anyway.
        transition(provider, state, ProviderHealth.UP);
    }

    @Override
    public void recordFailure(ProviderId provider) {
        State state = states.computeIfAbsent(provider, id -> new State());
        int failures = state.consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            transition(provider, state, ProviderHealth.DOWN);
        }
    }

    private void transition(ProviderId provider, State state, ProviderHealth target) {
        ProviderHealth previous = state.health.getAndSet(target);
        if (previous != target) {
            // Logged at info: an operator watching a failover wants to see exactly this line, and
            // it happens rarely enough that it cannot become noise.
            log.info("provider {} health {} -> {}", provider, previous, target);
        }
    }

    private static final class State {
        private final AtomicReference<ProviderHealth> health = new AtomicReference<>(ProviderHealth.UNKNOWN);
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
    }
}

package io.github.mehmetztrk.llmgateway.adapter.out.provider.mock;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knobs for {@link MockProvider}.
 *
 * @param enabled the mock is on by default in every profile — it is what makes the test suite
 *     runnable with no infrastructure at all.
 * @param models model names this mock answers to. Kept distinct from real model names so a
 *     benchmark can never accidentally hit a real model, or vice versa.
 * @param latency artificial delay before responding. Default is zero so correctness tests are not
 *     slowed down; benchmark profiles raise it to imitate a real provider.
 * @param completionTokens how many tokens to "generate". Fixed, because token counts feed quota and
 *     cost assertions that need a known input.
 * @param errorRate probability in [0, 1] of failing the call, evaluated deterministically per
 *     request. Used to exercise retry, circuit breaking and failover without unplugging anything.
 * @param seed base seed; change it to get a different but still reproducible universe.
 */
public record MockProviderProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue({"mock-fast", "mock-echo"}) Set<String> models,
        @DefaultValue("0ms") Duration latency,
        @DefaultValue("32") int completionTokens,
        @DefaultValue("0.0") double errorRate,
        @DefaultValue("42") long seed) {

    public MockProviderProperties {
        if (errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException("errorRate must be within [0.0, 1.0], was " + errorRate);
        }
        if (completionTokens < 0) {
            throw new IllegalArgumentException("completionTokens must not be negative");
        }
        models = Set.copyOf(models);
    }
}

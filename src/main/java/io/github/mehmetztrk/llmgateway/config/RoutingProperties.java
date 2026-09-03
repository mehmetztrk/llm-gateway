package io.github.mehmetztrk.llmgateway.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code gateway.routing}.
 *
 * @param aliases maps a name a tenant asks for onto an ordered list of real targets. Order is
 *     preference: the first healthy target wins, and the rest are the failover chain.
 * @param probeInterval how often each provider is probed in the background
 * @param probeTimeout how long a probe may take before the provider counts as down
 * @param failureThreshold consecutive live-traffic failures before a provider is marked down. One
 *     failure is noise; a handful in a row is a pattern.
 */
@ConfigurationProperties(prefix = "gateway.routing")
public record RoutingProperties(
        @DefaultValue Map<String, List<AliasTarget>> aliases,
        @DefaultValue("10s") Duration probeInterval,
        @DefaultValue("2s") Duration probeTimeout,
        @DefaultValue("3") int failureThreshold) {

    public RoutingProperties {
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
    }

    /**
     * @param provider the configured provider id, e.g. {@code ollama-primary}
     * @param model the model name that provider knows it by, which is often not the alias
     */
    public record AliasTarget(String provider, String model) {}
}

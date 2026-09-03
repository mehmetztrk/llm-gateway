package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.adapter.out.health.InMemoryProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.adapter.out.health.ProviderHealthMonitor;
import io.github.mehmetztrk.llmgateway.application.port.out.ProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.application.service.FailoverExecutor;
import io.github.mehmetztrk.llmgateway.application.service.ProviderRegistry;
import io.github.mehmetztrk.llmgateway.application.service.RoutingService;
import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires routing, health tracking and failover. */
@Configuration(proxyBeanMethods = false)
public class RoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfig.class);

    @Bean
    public ProviderHealthRegistry providerHealthRegistry(RoutingProperties properties) {
        return new InMemoryProviderHealthRegistry(properties.failureThreshold());
    }

    @Bean
    public RoutingService routingService(
            ProviderRegistry providers, ProviderHealthRegistry health, RoutingProperties properties) {

        Map<String, List<RouteTarget>> aliases = properties.aliases().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(target -> RouteTarget.of(target.provider(), target.model()))
                                .toList()));

        aliases.forEach((alias, targets) -> log.info("route {} -> {}", alias, targets));
        return new RoutingService(providers, health, aliases);
    }

    @Bean
    public FailoverExecutor failoverExecutor(ProviderRegistry providers, ProviderHealthRegistry health) {
        return new FailoverExecutor(providers, health);
    }

    @Bean
    public ProviderHealthMonitor providerHealthMonitor(
            ProviderRegistry providers, ProviderHealthRegistry health, RoutingProperties properties) {
        return new ProviderHealthMonitor(providers, health, properties.probeInterval(), properties.probeTimeout());
    }
}

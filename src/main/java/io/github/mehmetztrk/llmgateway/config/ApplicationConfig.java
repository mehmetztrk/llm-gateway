package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.application.port.in.AdminUseCase;
import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyFactory;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.application.service.AdminService;
import io.github.mehmetztrk.llmgateway.application.service.CacheService;
import io.github.mehmetztrk.llmgateway.application.service.ChatCompletionService;
import io.github.mehmetztrk.llmgateway.application.service.FailoverExecutor;
import io.github.mehmetztrk.llmgateway.application.service.RateLimitService;
import io.github.mehmetztrk.llmgateway.application.service.RoutingService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application layer.
 *
 * <p>This class exists so that no class under {@code application} needs a Spring import — enforced
 * by {@code ArchitectureTest}. The payoff is concrete: every use case can be constructed in a unit
 * test with {@code new}, with no context to start and no annotation to understand. The cost is this
 * file: a few lines of explicit wiring instead of component scanning.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {

    /**
     * why inject a Clock rather than call {@code Instant.now()}: time-dependent behaviour (quota
     * windows in M4, cache TTLs in M6) then becomes testable by substituting a fixed clock, instead
     * of by sleeping — which CLAUDE.md forbids outright.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ChatCompletionUseCase chatCompletionUseCase(
            RoutingService routing, FailoverExecutor failover, RateLimitService limits, CacheService cache) {
        return new ChatCompletionService(routing, failover, limits, cache);
    }

    @Bean
    public AdminUseCase adminUseCase(TenantRepository tenants, ApiKeyFactory keyFactory, ApiKeyHasher hasher) {
        return new AdminService(tenants, keyFactory, hasher);
    }

    @Bean
    public SecurityBootstrap securityBootstrap(
            TenantRepository tenants, ApiKeyHasher hasher, ApiKeyFactory keyFactory, SecurityProperties properties) {
        return new SecurityBootstrap(tenants, hasher, keyFactory, properties);
    }
}

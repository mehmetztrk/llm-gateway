package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.adapter.out.persistence.BufferedUsageLedger;
import io.github.mehmetztrk.llmgateway.adapter.out.persistence.CachingTenantRepository;
import io.github.mehmetztrk.llmgateway.adapter.out.persistence.JdbcTenantRepository;
import io.github.mehmetztrk.llmgateway.adapter.out.security.ApiKeyGenerator;
import io.github.mehmetztrk.llmgateway.adapter.out.security.HmacApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.in.UsageQueryUseCase;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.application.service.UsageQueryService;
import io.github.mehmetztrk.llmgateway.application.service.UsageRecorder;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Wires persistence and the boundary between blocking JDBC and the reactive request path. */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /**
     * The scheduler every blocking call is pushed onto.
     *
     * <p>why virtual threads rather than {@code Schedulers.boundedElastic()}: boundedElastic caps
     * at roughly ten platform threads per core and queues the rest, so a burst of cache misses
     * queues behind a small pool. A virtual thread per task costs a few hundred bytes and parks
     * without holding an OS thread, so the real limit becomes the connection pool — which is the
     * thing that actually has a limit. This is the one place in the codebase where Java 21's
     * virtual threads earn their keep, precisely because it is where genuinely blocking code lives.
     *
     * <p>The pool is not unbounded protection: HikariCP still caps concurrent connections, and a
     * task waiting for one parks its virtual thread rather than an event-loop thread. That is the
     * whole point.
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler blockingScheduler() {
        return Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor(), "blocking-jdbc");
    }

    @Bean
    public TenantRepository tenantRepository(JdbcClient jdbcClient, SecurityProperties properties) {
        return new CachingTenantRepository(
                new JdbcTenantRepository(jdbcClient), properties.cacheTtl(), properties.cacheMaxSize());
    }

    @Bean
    public UsageLedger usageLedger(JdbcClient jdbcClient) {
        // Capacity is generous relative to the flush interval: at any realistic request rate the
        // drainer empties it long before it fills, and the bound exists for the pathological case
        // where the database is slow, not the normal one.
        return new BufferedUsageLedger(jdbcClient, 10_000, 200, java.time.Duration.ofMillis(200));
    }

    @Bean
    public UsageQueryUseCase usageQueryUseCase(UsageLedger ledger) {
        return new UsageQueryService(ledger);
    }

    @Bean
    public UsageRecorder usageRecorder(UsageLedger ledger, PricingProperties pricing, java.time.Clock clock) {
        return new UsageRecorder(ledger, pricing.toPriceTable(), clock);
    }

    @Bean
    public ApiKeyHasher apiKeyHasher(SecurityProperties properties) {
        return new HmacApiKeyHasher(properties.keyPepper());
    }

    @Bean
    public ApiKeyGenerator apiKeyGenerator() {
        return new ApiKeyGenerator();
    }
}

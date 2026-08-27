package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.adapter.out.persistence.CachingTenantRepository;
import io.github.mehmetztrk.llmgateway.adapter.out.persistence.JdbcTenantRepository;
import io.github.mehmetztrk.llmgateway.adapter.out.security.ApiKeyGenerator;
import io.github.mehmetztrk.llmgateway.adapter.out.security.HmacApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
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
    public ApiKeyHasher apiKeyHasher(SecurityProperties properties) {
        return new HmacApiKeyHasher(properties.keyPepper());
    }

    @Bean
    public ApiKeyGenerator apiKeyGenerator() {
        return new ApiKeyGenerator();
    }
}

package io.github.mehmetztrk.llmgateway.blockhound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mehmetztrk.llmgateway.adapter.out.provider.mock.MockProvider;
import io.github.mehmetztrk.llmgateway.adapter.out.provider.mock.MockProviderProperties;
import io.github.mehmetztrk.llmgateway.application.port.in.GatewayResult;
import io.github.mehmetztrk.llmgateway.application.port.out.ResponseCache;
import io.github.mehmetztrk.llmgateway.application.service.CacheService;
import io.github.mehmetztrk.llmgateway.application.service.ChatCompletionService;
import io.github.mehmetztrk.llmgateway.application.service.FailoverExecutor;
import io.github.mehmetztrk.llmgateway.application.service.ProviderRegistry;
import io.github.mehmetztrk.llmgateway.application.service.RateLimitService;
import io.github.mehmetztrk.llmgateway.application.service.RoutingService;
import io.github.mehmetztrk.llmgateway.domain.cache.CachedCompletion;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import io.github.mehmetztrk.llmgateway.health.RecordingHealthRegistry;
import io.github.mehmetztrk.llmgateway.limits.InMemoryQuotaStore;
import io.github.mehmetztrk.llmgateway.limits.InMemoryRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Proves that the completion pipeline never blocks a non-blocking thread.
 *
 * <p>ADR-0002 chose WebFlux, and the price of that choice is that a single blocking call on an
 * event-loop thread degrades throughput in a way ordinary tests cannot see — the suite still
 * passes, and the damage only shows up under load. BlockHound instruments the JVM so that a
 * blocking call from a non-blocking thread throws instead of quietly succeeding.
 *
 * <p><b>why this lives in its own source set and its own JVM.</b> {@code BlockHound.install()} is
 * global and permanent for the process. Installed alongside the rest of the suite, Spring context
 * start-up trips it on framework code that is legitimately blocking, and the only way to get green
 * again is an allow-list so broad that the tool stops detecting anything. A separate Gradle test
 * task scopes the instrumentation to the code it is meant to police. See {@code build.gradle.kts}.
 */
class NonBlockingPathTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final RateLimitPolicy GENEROUS = new RateLimitPolicy(1_000_000, 1_000_000_000L);

    private static final ResponseCache NO_CACHE = new ResponseCache() {
        @Override
        public Mono<CachedCompletion> lookup(TenantId tenant, ChatRequest request) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> store(TenantId tenant, ChatRequest request, Completion completion) {
            return Mono.empty();
        }
    };

    @BeforeAll
    static void installBlockHound() {
        BlockHound.install();
    }

    private ChatCompletionService service() {
        MockProvider provider = new MockProvider(
                ProviderId.of("mock"),
                new MockProviderProperties(true, Set.of("mock-fast"), Duration.ZERO, Duration.ZERO, 16, 0.0, -1, 42L),
                FIXED);
        ProviderRegistry registry = new ProviderRegistry(List.of(provider));
        RecordingHealthRegistry health = new RecordingHealthRegistry();
        RoutingService routing = new RoutingService(registry, health, Map.of());
        return new ChatCompletionService(
                routing,
                new FailoverExecutor(registry, health),
                new RateLimitService(new InMemoryRateLimiter(), new InMemoryQuotaStore()),
                new CacheService(NO_CACHE, NO_CACHE, false));
    }

    private AuthenticatedCaller caller() {
        Tenant tenant = new Tenant(
                TenantId.random(), "bh", true, ModelAllowList.ANY, GENEROUS, QuotaPolicy.UNLIMITED, FIXED.instant());
        return new AuthenticatedCaller(tenant, UUID.randomUUID(), ApiKeyRole.TENANT, GENEROUS);
    }

    private ChatRequest request() {
        return ChatRequest.of("mock-fast", ChatMessage.user("hello"));
    }

    @Test
    @DisplayName("BlockHound is actually armed")
    void blockHoundIsArmed() {
        // A test suite that silently fails to instrument the JVM would pass every assertion below
        // while proving nothing. Verify the detector works before trusting it.
        assertThatThrownBy(() -> Mono.fromCallable(() -> {
                            Thread.sleep(1);
                            return "should not get here";
                        })
                        .subscribeOn(Schedulers.parallel())
                        .block())
                .hasRootCauseInstanceOf(BlockingOperationError.class);
    }

    @Test
    @DisplayName("a non-streamed completion runs without blocking a non-blocking thread")
    void completionDoesNotBlock() {
        String content = service()
                .complete(caller(), request())
                .subscribeOn(Schedulers.parallel())
                .map(result -> result.body().message().content())
                .block(Duration.ofSeconds(10));

        assertThat(content).isNotBlank();
    }

    @Test
    @DisplayName("a streamed completion runs without blocking a non-blocking thread")
    void streamingDoesNotBlock() {
        Long chunks = service().stream(caller(), request())
                .flatMapMany(GatewayResult::body)
                .subscribeOn(Schedulers.parallel())
                .count()
                .block(Duration.ofSeconds(10));

        assertThat(chunks).isEqualTo(17L);
    }
}

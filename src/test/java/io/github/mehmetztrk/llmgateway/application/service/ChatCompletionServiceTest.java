package io.github.mehmetztrk.llmgateway.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotAllowed;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotFound;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.error.RateLimitExceeded;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import io.github.mehmetztrk.llmgateway.health.RecordingHealthRegistry;
import io.github.mehmetztrk.llmgateway.limits.InMemoryQuotaStore;
import io.github.mehmetztrk.llmgateway.limits.InMemoryRateLimiter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Pure unit tests of the pipeline's <em>ordering</em>: policy, then admission, then the provider.
 *
 * <p>No Spring context, no HTTP, no containers — what the "application layer has zero framework
 * imports" rule buys. Routing and failover have their own tests; this one is about what happens
 * before either is reached.
 */
class ChatCompletionServiceTest {

    private static final ProviderId STUB = ProviderId.of("stub");
    private static final RateLimitPolicy GENEROUS = new RateLimitPolicy(1_000_000, 1_000_000_000L);

    private final InMemoryRateLimiter rateLimiter = new InMemoryRateLimiter();
    private final InMemoryQuotaStore quotas = new InMemoryQuotaStore();

    private record StubProvider(ProviderId id, Set<String> supportedModels, Mono<Completion> response)
            implements LlmProvider {
        @Override
        public Mono<Completion> complete(ChatRequest request) {
            return response;
        }

        @Override
        public Flux<CompletionChunk> stream(ChatRequest request) {
            return response.flatMapMany(completion -> Flux.just(
                    new CompletionChunk.Delta(
                            completion.id(),
                            completion.model(),
                            completion.servedBy(),
                            completion.message().content(),
                            completion.createdAt()),
                    new CompletionChunk.Done(
                            completion.id(),
                            completion.model(),
                            completion.servedBy(),
                            completion.finishReason(),
                            completion.usage(),
                            completion.createdAt())));
        }

        @Override
        public Mono<Boolean> isHealthy() {
            return Mono.just(true);
        }
    }

    private static Completion completion() {
        return new Completion(
                "id-1",
                "stub-model",
                STUB,
                ChatMessage.assistant("hello"),
                new TokenUsage(3, 4),
                FinishReason.STOP,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static AuthenticatedCaller callerAllowing(ModelAllowList models) {
        return new AuthenticatedCaller(
                new Tenant(
                        TenantId.random(),
                        "test-tenant",
                        true,
                        models,
                        GENEROUS,
                        QuotaPolicy.UNLIMITED,
                        Instant.parse("2026-01-01T00:00:00Z")),
                UUID.randomUUID(),
                ApiKeyRole.TENANT,
                GENEROUS);
    }

    private ChatCompletionService serviceWith(LlmProvider provider) {
        ProviderRegistry registry = new ProviderRegistry(List.of(provider));
        RecordingHealthRegistry health = new RecordingHealthRegistry();
        RoutingService routing = new RoutingService(
                registry, health, Map.of("alias", List.of(new RouteTarget(provider.id(), "stub-model"))));
        return new ChatCompletionService(
                routing, new FailoverExecutor(registry, health), new RateLimitService(rateLimiter, quotas));
    }

    private ChatCompletionService workingService() {
        return serviceWith(new StubProvider(STUB, Set.of("stub-model"), Mono.just(completion())));
    }

    /**
     * A provider that counts calls instead of throwing.
     *
     * <p>why not simply {@code Mono.error(new AssertionError("called"))}: failover normalises any
     * error a provider raises into NoProviderAvailable, so an assertion thrown from inside one is
     * swallowed and the test passes for the wrong reason. Counting is unswallowable.
     */
    private record CountingProvider(
            ProviderId id, Set<String> supportedModels, java.util.concurrent.atomic.AtomicInteger calls)
            implements LlmProvider {
        @Override
        public Mono<Completion> complete(ChatRequest request) {
            calls.incrementAndGet();
            return Mono.just(completion());
        }

        @Override
        public Flux<CompletionChunk> stream(ChatRequest request) {
            calls.incrementAndGet();
            return Flux.empty();
        }

        @Override
        public Mono<Boolean> isHealthy() {
            return Mono.just(true);
        }
    }

    @Test
    @DisplayName("a permitted request is served and reports its limit state")
    void servesPermittedRequest() {
        StepVerifier.create(workingService()
                        .complete(
                                callerAllowing(ModelAllowList.ANY),
                                ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .assertNext(result -> {
                    assertThat(result.body().servedBy()).isEqualTo(STUB);
                    assertThat(result.requests().limit()).isEqualTo(GENEROUS.requestsPerMinute());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an alias resolves through routing to the underlying provider")
    void aliasIsRouted() {
        StepVerifier.create(workingService()
                        .complete(callerAllowing(ModelAllowList.ANY), ChatRequest.of("alias", ChatMessage.user("hi"))))
                .assertNext(result -> assertThat(result.body().servedBy()).isEqualTo(STUB))
                .verifyComplete();
    }

    @Test
    @DisplayName("an unknown model fails with ModelNotFound, delivered as an error signal")
    void unknownModelFails() {
        // The important half is *how* it fails: routing throws, and without Mono.defer in the
        // service that throw would escape synchronously before a subscriber ever exists.
        StepVerifier.create(workingService()
                        .complete(callerAllowing(ModelAllowList.ANY), ChatRequest.of("nope", ChatMessage.user("hi"))))
                .expectError(ModelNotFound.class)
                .verify();
    }

    @Test
    @DisplayName("the tenant policy is checked before any provider is contacted")
    void enforcesTenantPolicyBeforeRouting() {
        // Ordering matters: a model a tenant may not use must cost zero upstream calls, or a
        // rejected request would still consume provider capacity and, later, quota.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ChatCompletionService service = serviceWith(new CountingProvider(STUB, Set.of("stub-model"), calls));

        StepVerifier.create(service.complete(
                        callerAllowing(ModelAllowList.of("something-else")),
                        ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectError(ModelNotAllowed.class)
                .verify();

        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("the tenant policy is enforced on the streaming path too")
    void enforcesTenantPolicyWhenStreaming() {
        // Easy to implement on one path and forget on the other, so it is asserted on both.
        StepVerifier.create(workingService().stream(
                        callerAllowing(ModelAllowList.NONE), ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectError(ModelNotAllowed.class)
                .verify();
    }

    @Test
    @DisplayName("admission runs before the provider: an exhausted bucket costs no upstream call")
    void enforcesLimitsBeforeCallingProvider() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ChatCompletionService service = serviceWith(new CountingProvider(STUB, Set.of("stub-model"), calls));
        AuthenticatedCaller caller = callerAllowing(ModelAllowList.ANY);
        rateLimiter.exhaust("tenant:" + caller.tenantId().value() + ":req");

        StepVerifier.create(service.complete(caller, ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectError(RateLimitExceeded.class)
                .verify();

        assertThat(calls)
                .as("admission must run before the provider is contacted")
                .hasValue(0);
    }

    @Test
    @DisplayName("actual usage is settled against the quota after the provider answers")
    void settlesUsageAfterCompletion() {
        AuthenticatedCaller caller = callerAllowing(ModelAllowList.ANY);

        workingService()
                .complete(caller, ChatRequest.of("stub-model", ChatMessage.user("hi")))
                .block();

        // 3 prompt + 4 completion tokens from the stub.
        assertThat(quotas.usedBy(caller.tenantId())).isEqualTo(7);
    }

    @Test
    @DisplayName("a provider failure with no fallback surfaces as an error, not an empty response")
    void providerFailureSurfaces() {
        ChatCompletionService service = serviceWith(
                new StubProvider(STUB, Set.of("stub-model"), Mono.error(new ProviderCallFailed(STUB, "down"))));

        StepVerifier.create(service.complete(
                        callerAllowing(ModelAllowList.ANY), ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectError()
                .verify();
    }
}

package io.github.mehmetztrk.llmgateway.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Pure unit tests: no Spring context, no HTTP, no containers. This is what the "application layer
 * has zero framework imports" rule buys — the whole pipeline is exercised with {@code new} and a
 * hand-written stub, in milliseconds.
 */
class ChatCompletionServiceTest {

    private static final ProviderId MOCK = ProviderId.of("stub");

    /** A caller allowed to use every model, so these tests isolate routing from policy. */
    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller(
            new Tenant(
                    TenantId.random(), "test-tenant", true, ModelAllowList.ANY, Instant.parse("2026-01-01T00:00:00Z")),
            UUID.randomUUID(),
            ApiKeyRole.TENANT);

    /** A minimal hand-rolled stub. why not Mockito: this is shorter, and it compiles. */
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
    }

    private static Completion completion() {
        return new Completion(
                "id-1",
                "stub-model",
                MOCK,
                ChatMessage.assistant("hello"),
                new TokenUsage(3, 4),
                FinishReason.STOP,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private ChatCompletionService serviceWith(LlmProvider... providers) {
        return new ChatCompletionService(new ProviderRegistry(List.of(providers)));
    }

    @Test
    @DisplayName("routes to the provider that serves the requested model")
    void routesToSupportingProvider() {
        LlmProvider other = new StubProvider(ProviderId.of("other"), Set.of("other-model"), Mono.empty());
        LlmProvider target = new StubProvider(MOCK, Set.of("stub-model"), Mono.just(completion()));

        StepVerifier.create(serviceWith(other, target)
                        .complete(CALLER, ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .assertNext(result -> assertThat(result.servedBy()).isEqualTo(MOCK))
                .verifyComplete();
    }

    @Test
    @DisplayName("an unknown model fails with ModelNotFound, delivered as an error signal")
    void unknownModelFails() {
        // The important half of this test is *how* it fails: requireProviderFor throws, and
        // without Mono.defer in the service that throw would escape as a synchronous exception
        // before a subscriber ever exists.
        ChatCompletionService service =
                serviceWith(new StubProvider(MOCK, Set.of("stub-model"), Mono.just(completion())));

        StepVerifier.create(service.complete(CALLER, ChatRequest.of("nope", ChatMessage.user("hi"))))
                .expectError(ModelNotFound.class)
                .verify();
    }

    @Test
    @DisplayName("a raw exception from a provider is normalised to ProviderCallFailed")
    void normalisesUnexpectedProviderErrors() {
        LlmProvider broken =
                new StubProvider(MOCK, Set.of("stub-model"), Mono.error(new IllegalStateException("kaboom")));

        StepVerifier.create(serviceWith(broken).complete(CALLER, ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderCallFailed.class);
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                })
                .verify();
    }

    @Test
    @DisplayName("a domain error from a provider passes through unchanged")
    void preservesDomainErrors() {
        ProviderCallFailed original = new ProviderCallFailed(MOCK, "upstream down");
        LlmProvider broken = new StubProvider(MOCK, Set.of("stub-model"), Mono.error(original));

        StepVerifier.create(serviceWith(broken).complete(CALLER, ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(original))
                .verify();
    }

    @Test
    @DisplayName("the tenant policy is checked before any provider is contacted")
    void enforcesTenantPolicyBeforeRouting() {
        // Ordering matters: a model a tenant may not use must cost zero upstream calls, or a
        // rejected request would still consume provider capacity and, later, quota.
        AuthenticatedCaller restricted = new AuthenticatedCaller(
                new Tenant(
                        TenantId.random(),
                        "restricted",
                        true,
                        ModelAllowList.of("something-else"),
                        Instant.parse("2026-01-01T00:00:00Z")),
                UUID.randomUUID(),
                ApiKeyRole.TENANT);

        LlmProvider provider = new StubProvider(
                MOCK, Set.of("stub-model"), Mono.error(new AssertionError("provider must not be called")));

        StepVerifier.create(serviceWith(provider)
                        .complete(restricted, ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectError(ModelNotAllowed.class)
                .verify();
    }

    @Test
    @DisplayName("the tenant policy is enforced on the streaming path too")
    void enforcesTenantPolicyWhenStreaming() {
        // Easy to implement on one path and forget on the other, so it is asserted on both.
        AuthenticatedCaller restricted = new AuthenticatedCaller(
                new Tenant(
                        TenantId.random(),
                        "restricted",
                        true,
                        ModelAllowList.NONE,
                        Instant.parse("2026-01-01T00:00:00Z")),
                UUID.randomUUID(),
                ApiKeyRole.TENANT);

        LlmProvider provider = new StubProvider(MOCK, Set.of("stub-model"), Mono.just(completion()));

        StepVerifier.create(
                        serviceWith(provider).stream(restricted, ChatRequest.of("stub-model", ChatMessage.user("hi"))))
                .expectError(ModelNotAllowed.class)
                .verify();
    }

    @Test
    @DisplayName("duplicate provider ids are rejected at startup, not at first request")
    void rejectsDuplicateProviderIds() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(
                        new StubProvider(MOCK, Set.of("a"), Mono.empty()),
                        new StubProvider(MOCK, Set.of("b"), Mono.empty()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate provider id");
    }
}

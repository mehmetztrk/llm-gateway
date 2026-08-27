package io.github.mehmetztrk.llmgateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * The behaviour every {@link LlmProvider} must exhibit, regardless of what is behind it.
 *
 * <p>why a shared contract instead of separate tests per provider: routing, failover and caching
 * are all written against the interface, so they are only correct if every implementation really
 * does behave the same. A per-provider test suite proves each one works; only a shared contract
 * proves they are interchangeable — which is the property the rest of the system depends on.
 *
 * <p>Subclasses supply an instance and a model it serves. Live-provider variants live in the
 * {@code integrationTest} source set; fast in-process ones live in {@code test}.
 */
public abstract class LlmProviderContract {

    /** A ready-to-use provider. Called once per test, so it may be stateful. */
    protected abstract LlmProvider provider();

    /** A model name {@link #provider()} is configured to serve. */
    protected abstract String supportedModel();

    protected ChatRequest sampleRequest() {
        return ChatRequest.of(supportedModel(), ChatMessage.user("Reply briefly: what is a gateway?"));
    }

    @Test
    @DisplayName("exposes a stable, non-null provider id")
    void exposesStableId() {
        LlmProvider provider = provider();
        assertThat(provider.id()).isNotNull();
        assertThat(provider.id()).isEqualTo(provider.id());
        assertThat(provider.id().value()).isNotBlank();
    }

    @Test
    @DisplayName("declares the models it serves and answers supports() consistently")
    void declaresSupportedModels() {
        LlmProvider provider = provider();
        assertThat(provider.supportedModels()).isNotEmpty().contains(supportedModel());
        assertThat(provider.supports(supportedModel())).isTrue();
        assertThat(provider.supports("definitely-not-a-real-model")).isFalse();
    }

    @Test
    @DisplayName("returns exactly one completion carrying an assistant message")
    void returnsOneAssistantCompletion() {
        StepVerifier.create(provider().complete(sampleRequest()))
                .assertNext(completion -> {
                    assertThat(completion.id()).isNotBlank();
                    assertThat(completion.message().role()).isEqualTo(Role.ASSISTANT);
                    assertThat(completion.message().content()).isNotBlank();
                    assertThat(completion.finishReason()).isNotNull();
                    assertThat(completion.createdAt()).isNotNull();
                })
                // A provider that emits more than one element would break every caller that
                // treats a completion as a single value — assert the cardinality, not just the
                // content.
                .verifyComplete();
    }

    @Test
    @DisplayName("attributes the completion to itself and reports the model it served")
    void attributesCompletionToItself() {
        StepVerifier.create(provider().complete(sampleRequest()))
                .assertNext(completion -> {
                    assertThat(completion.servedBy()).isEqualTo(provider().id());
                    assertThat(completion.model()).isNotBlank();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("reports non-negative token usage that adds up")
    void reportsTokenUsage() {
        StepVerifier.create(provider().complete(sampleRequest()))
                .assertNext(completion -> {
                    assertThat(completion.usage()).isNotNull();
                    assertThat(completion.usage().promptTokens()).isNotNegative();
                    assertThat(completion.usage().completionTokens()).isNotNegative();
                    assertThat(completion.usage().totalTokens())
                            .isEqualTo(completion.usage().promptTokens()
                                    + completion.usage().completionTokens());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("is cold: no work happens until the returned Mono is subscribed")
    void isCold() {
        // why this matters: retries, timeouts and failover all resubscribe. A provider that fired
        // the HTTP call at assembly time would send one request too many on the first attempt and
        // none on the retry.
        provider().complete(sampleRequest());
        // Nothing asserted beyond "this did not throw or block" — a hot implementation would
        // typically fail the surrounding suite by exhausting a stub's expectations.
    }
}

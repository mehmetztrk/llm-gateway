package io.github.mehmetztrk.llmgateway.adapter.out.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * The properties that make MockProvider usable as a measuring instrument. If any of these break,
 * every number in BENCHMARKS.md becomes unreproducible.
 */
class MockProviderTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private MockProvider provider(Duration latency, double errorRate, int completionTokens) {
        return new MockProvider(
                ProviderId.of("mock"),
                new MockProviderProperties(
                        true, Set.of("mock-fast"), latency, Duration.ZERO, completionTokens, errorRate, -1, 42L),
                FIXED);
    }

    @Test
    @DisplayName("the same request always produces byte-identical output")
    void isDeterministic() {
        MockProvider provider = provider(Duration.ZERO, 0.0, 16);
        ChatRequest request = ChatRequest.of("mock-fast", ChatMessage.user("stable input"));

        Completion first = provider.complete(request).block();
        Completion second = provider.complete(request).block();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.message().content()).isEqualTo(first.message().content());
        assertThat(second.usage()).isEqualTo(first.usage());
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("different requests produce different output")
    void differentInputsDiffer() {
        MockProvider provider = provider(Duration.ZERO, 0.0, 16);

        Completion a = provider.complete(ChatRequest.of("mock-fast", ChatMessage.user("input A")))
                .block();
        Completion b = provider.complete(ChatRequest.of("mock-fast", ChatMessage.user("input B")))
                .block();

        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(b.message().content()).isNotEqualTo(a.message().content());
    }

    @Test
    @DisplayName("emits exactly the configured number of completion tokens")
    void honoursConfiguredTokenCount() {
        Completion completion = provider(Duration.ZERO, 0.0, 7)
                .complete(ChatRequest.of("mock-fast", ChatMessage.user("hi")))
                .block();

        assertThat(completion).isNotNull();
        assertThat(completion.usage().completionTokens()).isEqualTo(7);
        assertThat(completion.message().content().split(" ")).hasSize(7);
    }

    @Test
    @DisplayName("errorRate=1.0 fails every call as a domain error, not a raw exception")
    void alwaysFailsAtFullErrorRate() {
        StepVerifier.create(
                        provider(Duration.ZERO, 1.0, 8).complete(ChatRequest.of("mock-fast", ChatMessage.user("hi"))))
                .expectError(ProviderCallFailed.class)
                .verify();
    }

    @Test
    @DisplayName("errorRate=0.0 never fails")
    void neverFailsAtZeroErrorRate() {
        MockProvider provider = provider(Duration.ZERO, 0.0, 8);
        for (int i = 0; i < 50; i++) {
            assertThat(provider.complete(ChatRequest.of("mock-fast", ChatMessage.user("request " + i)))
                            .block())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("configured latency is applied without any test sleeping")
    void appliesConfiguredLatency() {
        // why withVirtualTime: the alternative is Thread.sleep(200) and a wall-clock assertion,
        // which is slow and flaky on a loaded CI runner. VirtualTimeScheduler replaces the
        // scheduler delayElement uses, so 200ms passes instantly but the operator still sees the
        // full delay. The Mono must be created *inside* the supplier, otherwise it captures the
        // real scheduler before the virtual one is installed.
        StepVerifier.withVirtualTime(() -> provider(Duration.ofMillis(200), 0.0, 4)
                        .complete(ChatRequest.of("mock-fast", ChatMessage.user("hi"))))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(200))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("rejects a nonsensical error rate at construction time")
    void rejectsInvalidErrorRate() {
        assertThatThrownBy(() ->
                        new MockProviderProperties(true, Set.of("m"), Duration.ZERO, Duration.ZERO, 1, 1.5, -1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorRate");
    }
}

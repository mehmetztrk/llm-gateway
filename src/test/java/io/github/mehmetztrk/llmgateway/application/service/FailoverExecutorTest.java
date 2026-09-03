package io.github.mehmetztrk.llmgateway.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.error.NoProviderAvailable;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderHealth;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import io.github.mehmetztrk.llmgateway.health.RecordingHealthRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FailoverExecutorTest {

    private static final ProviderId ALPHA = ProviderId.of("alpha");
    private static final ProviderId BETA = ProviderId.of("beta");

    private final RecordingHealthRegistry health = new RecordingHealthRegistry();

    private static Completion completionFrom(ProviderId provider, String text) {
        return new Completion(
                "id",
                "model",
                provider,
                ChatMessage.assistant(text),
                new TokenUsage(1, 1),
                FinishReason.STOP,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    /** A provider whose behaviour is supplied per test, and which counts how often it was called. */
    private static final class ScriptedProvider implements LlmProvider {
        private final ProviderId id;
        private final Mono<Completion> completion;
        private final Flux<CompletionChunk> chunks;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedProvider(ProviderId id, Mono<Completion> completion, Flux<CompletionChunk> chunks) {
            this.id = id;
            this.completion = completion;
            this.chunks = chunks;
        }

        @Override
        public ProviderId id() {
            return id;
        }

        @Override
        public Set<String> supportedModels() {
            return Set.of("model");
        }

        @Override
        public Mono<Completion> complete(ChatRequest request) {
            calls.incrementAndGet();
            return completion;
        }

        @Override
        public Flux<CompletionChunk> stream(ChatRequest request) {
            calls.incrementAndGet();
            return chunks;
        }

        @Override
        public Mono<Boolean> isHealthy() {
            return Mono.just(true);
        }
    }

    private FailoverExecutor executor(LlmProvider... providers) {
        return new FailoverExecutor(new ProviderRegistry(List.of(providers)), health);
    }

    private ChatRequest request() {
        return ChatRequest.of("alias", ChatMessage.user("hi"));
    }

    private List<RouteTarget> route() {
        return List.of(new RouteTarget(ALPHA, "model-a"), new RouteTarget(BETA, "model-b"));
    }

    @Test
    @DisplayName("the first healthy candidate answers and the second is never called")
    void firstCandidateWins() {
        ScriptedProvider alpha =
                new ScriptedProvider(ALPHA, Mono.just(completionFrom(ALPHA, "from alpha")), Flux.empty());
        ScriptedProvider beta = new ScriptedProvider(BETA, Mono.just(completionFrom(BETA, "from beta")), Flux.empty());

        StepVerifier.create(executor(alpha, beta).complete(route(), request()))
                .assertNext(completion -> assertThat(completion.servedBy()).isEqualTo(ALPHA))
                .verifyComplete();

        assertThat(beta.calls).hasValue(0);
    }

    @Test
    @DisplayName("a failing primary falls over to the secondary")
    void failsOverOnError() {
        ScriptedProvider alpha =
                new ScriptedProvider(ALPHA, Mono.error(new ProviderCallFailed(ALPHA, "down")), Flux.empty());
        ScriptedProvider beta = new ScriptedProvider(BETA, Mono.just(completionFrom(BETA, "from beta")), Flux.empty());

        StepVerifier.create(executor(alpha, beta).complete(route(), request()))
                .assertNext(completion -> assertThat(completion.servedBy()).isEqualTo(BETA))
                .verifyComplete();

        assertThat(health.failures()).containsExactly(ALPHA);
        assertThat(health.successes()).containsExactly(BETA);
    }

    @Test
    @DisplayName("the target's own model name is sent upstream, not the alias")
    void rewritesModelPerTarget() {
        AtomicInteger seen = new AtomicInteger();
        LlmProvider alpha = new LlmProvider() {
            @Override
            public ProviderId id() {
                return ALPHA;
            }

            @Override
            public Set<String> supportedModels() {
                return Set.of("model-a");
            }

            @Override
            public Mono<Completion> complete(ChatRequest request) {
                // The whole point of aliases: each provider is asked for the model it knows.
                assertThat(request.model()).isEqualTo("model-a");
                seen.incrementAndGet();
                return Mono.just(completionFrom(ALPHA, "ok"));
            }

            @Override
            public Flux<CompletionChunk> stream(ChatRequest request) {
                return Flux.empty();
            }

            @Override
            public Mono<Boolean> isHealthy() {
                return Mono.just(true);
            }
        };

        executor(alpha)
                .complete(List.of(new RouteTarget(ALPHA, "model-a")), request())
                .block();
        assertThat(seen).hasValue(1);
    }

    @Test
    @DisplayName("when every candidate fails the caller gets NoProviderAvailable, not the last error")
    void exhaustedRouteFails() {
        ScriptedProvider alpha =
                new ScriptedProvider(ALPHA, Mono.error(new ProviderCallFailed(ALPHA, "down")), Flux.empty());
        ScriptedProvider beta =
                new ScriptedProvider(BETA, Mono.error(new ProviderCallFailed(BETA, "also down")), Flux.empty());

        StepVerifier.create(executor(alpha, beta).complete(route(), request()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(NoProviderAvailable.class);
                    assertThat(((NoProviderAvailable) error).attempted()).hasSize(2);
                })
                .verify();
    }

    @Test
    @DisplayName("a stream that fails before its first chunk falls over")
    void streamFailsOverBeforeFirstChunk() {
        ScriptedProvider alpha = new ScriptedProvider(
                ALPHA, Mono.empty(), Flux.error(new ProviderCallFailed(ALPHA, "refused connection")));
        ScriptedProvider beta = new ScriptedProvider(BETA, Mono.empty(), Flux.just(delta(BETA, "hello"), done(BETA)));

        StepVerifier.create(executor(alpha, beta).stream(route(), request()))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    @DisplayName("a stream that fails after its first chunk does NOT fall over")
    void streamDoesNotFailOverMidStream() {
        // The client already holds part of this answer. Starting a second provider would splice
        // two different responses together and hand the result over as though it were one.
        ScriptedProvider alpha = new ScriptedProvider(
                ALPHA,
                Mono.empty(),
                Flux.concat(Flux.just(delta(ALPHA, "half an ")), Flux.error(new ProviderCallFailed(ALPHA, "died"))));
        ScriptedProvider beta = new ScriptedProvider(BETA, Mono.empty(), Flux.just(delta(BETA, "whole"), done(BETA)));

        StepVerifier.create(executor(alpha, beta).stream(route(), request()))
                .expectNextCount(1)
                .expectError(ProviderCallFailed.class)
                .verify();

        assertThat(beta.calls).as("the fallback must not be contacted").hasValue(0);
    }

    @Test
    @DisplayName("health is recorded from live traffic, not only from probes")
    void recordsHealthFromTraffic() {
        ScriptedProvider alpha = new ScriptedProvider(ALPHA, Mono.just(completionFrom(ALPHA, "ok")), Flux.empty());

        executor(alpha)
                .complete(List.of(new RouteTarget(ALPHA, "model-a")), request())
                .block();

        assertThat(health.healthOf(ALPHA)).isEqualTo(ProviderHealth.UP);
    }

    private static CompletionChunk delta(ProviderId provider, String text) {
        return new CompletionChunk.Delta("id", "model", provider, text, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static CompletionChunk done(ProviderId provider) {
        return new CompletionChunk.Done(
                "id",
                "model",
                provider,
                FinishReason.STOP,
                new TokenUsage(1, 1),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}

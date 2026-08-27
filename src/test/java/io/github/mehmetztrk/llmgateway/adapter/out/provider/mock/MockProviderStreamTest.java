package io.github.mehmetztrk.llmgateway.adapter.out.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Streaming behaviour of the measuring instrument, including the failure modes M5 will rely on. */
class MockProviderStreamTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private MockProvider provider(int tokens, Duration chunkDelay, int failAfterChunks) {
        return new MockProvider(
                ProviderId.of("mock"),
                new MockProviderProperties(
                        true, Set.of("mock-fast"), Duration.ZERO, chunkDelay, tokens, 0.0, failAfterChunks, 42L),
                FIXED);
    }

    private ChatRequest request() {
        return ChatRequest.of("mock-fast", ChatMessage.user("hello"));
    }

    @Test
    @DisplayName("emits one Delta per token and a single trailing Done")
    void emitsDeltasThenDone() {
        List<CompletionChunk> chunks =
                provider(5, Duration.ZERO, -1).stream(request()).collectList().block();

        assertThat(chunks).hasSize(6);
        assertThat(chunks.subList(0, 5)).allMatch(CompletionChunk.Delta.class::isInstance);
        assertThat(chunks.getLast()).isInstanceOf(CompletionChunk.Done.class);

        CompletionChunk.Done done = (CompletionChunk.Done) chunks.getLast();
        assertThat(done.usage().completionTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("the streamed text is identical across runs")
    void streamIsDeterministic() {
        String first = assemble(provider(8, Duration.ZERO, -1).stream(request()));
        String second = assemble(provider(8, Duration.ZERO, -1).stream(request()));
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("streamed text matches the non-streamed answer for the same request")
    void streamMatchesNonStreamed() {
        // If these ever diverge, a client would get a different answer depending on whether it
        // asked for a stream — which would make the cache in M6 quietly wrong.
        MockProvider provider = provider(8, Duration.ZERO, -1);
        String streamed = assemble(provider.stream(request()));
        String whole = provider.complete(request()).block().message().content();
        assertThat(streamed).isEqualTo(whole);
    }

    @Test
    @DisplayName("failAfterChunks emits that many chunks and then errors")
    void failsMidStream() {
        StepVerifier.create(provider(20, Duration.ZERO, 3).stream(request()))
                .expectNextCount(3)
                .expectErrorSatisfies(error ->
                        assertThat(error).isInstanceOf(ProviderCallFailed.class).hasMessageContaining("mid-stream"))
                .verify();
    }

    @Test
    @DisplayName("produces strictly on demand: three requests yield exactly three chunks")
    void isDemandDriven() {
        // The instrumented counter is the point: it proves the *source* never ran ahead, which a
        // plain "only three arrived" assertion could not distinguish from buffering.
        AtomicInteger delivered = new AtomicInteger();
        Flux<CompletionChunk> counted =
                provider(100, Duration.ZERO, -1).stream(request()).doOnNext(chunk -> delivered.incrementAndGet());

        StepVerifier.create(counted, 3)
                .expectNextCount(3)
                .then(() -> assertThat(delivered).hasValue(3))
                .thenRequest(2)
                .expectNextCount(2)
                .then(() -> assertThat(delivered).hasValue(5))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("chunkDelay paces the stream without any test sleeping")
    void pacesChunks() {
        StepVerifier.withVirtualTime(() -> provider(3, Duration.ofMillis(100), -1).stream(request()))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .expectNextCount(1)
                .expectNoEvent(Duration.ofMillis(100))
                .expectNextCount(1)
                .thenCancel()
                .verify();
    }

    private String assemble(Flux<CompletionChunk> chunks) {
        return chunks.filter(CompletionChunk.Delta.class::isInstance)
                .map(chunk -> ((CompletionChunk.Delta) chunk).content())
                .reduce("", String::concat)
                .block();
    }
}

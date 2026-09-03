package io.github.mehmetztrk.llmgateway.adapter.out.provider.mock;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.CRC32;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A provider with no model behind it, used to measure the gateway itself.
 *
 * <p>This is the most important test double in the project. Every number in BENCHMARKS.md is taken
 * against this provider, because a real model's latency variance is orders of magnitude larger than
 * the gateway overhead being measured — running the benchmark against Ollama would measure the GPU,
 * not the code.
 *
 * <p><b>Determinism.</b> The same request always produces the same response, the same token counts
 * and the same success-or-failure decision. The seed is derived from the configured seed plus a
 * checksum of the request, not from a shared mutable {@link Random}: two concurrent callers must
 * not be able to influence each other's outcome, or a load test would stop being reproducible.
 */
public class MockProvider implements LlmProvider {

    private static final String[] LOREM = {
        "the",
        "gateway",
        "returns",
        "deterministic",
        "tokens",
        "for",
        "repeatable",
        "measurement",
        "without",
        "involving",
        "any",
        "model",
        "weights",
        "or",
        "hardware",
        "at",
        "all"
    };

    private final ProviderId id;
    private final MockProviderProperties properties;
    private final Clock clock;

    public MockProvider(ProviderId id, MockProviderProperties properties, Clock clock) {
        this.id = id;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ProviderId id() {
        return id;
    }

    @Override
    public Set<String> supportedModels() {
        return properties.models();
    }

    /**
     * why two independent Randoms per request instead of one: drawing the failure decision from the
     * same sequence that generates the text shifts that sequence by one. The streamed and
     * non-streamed paths would then produce different words for an identical request — a divergence
     * that would make the M6 cache silently wrong, since a cached streamed answer and a live
     * non-streamed one would no longer agree. Separate derivations keep both paths byte-identical.
     */
    private Random contentRandom(long seed) {
        return new Random(seed);
    }

    private Random failureRandom(long seed) {
        return new Random(seed * 31 + 7);
    }

    @Override
    public Mono<Completion> complete(ChatRequest request) {
        long seed = seedFor(request);

        Mono<Completion> result = Mono.defer(() -> {
            if (failureRandom(seed).nextDouble() < properties.errorRate()) {
                return Mono.<Completion>error(
                        new ProviderCallFailed(id, "simulated failure (errorRate=" + properties.errorRate() + ")"));
            }
            return Mono.just(build(request, contentRandom(seed)));
        });

        // why delayElement and not Thread.sleep: sleeping would block an event-loop thread, and
        // under load the benchmark would then measure the mock starving the gateway rather than
        // the gateway's own overhead.
        //
        // why the zero check: delayElement(ZERO) still hands the signal to the parallel scheduler,
        // costing a task submission and a thread hop on every call. At the microsecond scale this
        // provider exists to measure, that is not noise we can afford to add.
        return properties.latency().isZero() ? result : result.delayElement(properties.latency());
    }

    private Completion build(ChatRequest request, Random random) {
        int completionTokens = properties.completionTokens();
        StringJoiner content = new StringJoiner(" ");
        for (int i = 0; i < completionTokens; i++) {
            content.add(LOREM[random.nextInt(LOREM.length)]);
        }

        int promptTokens = estimatePromptTokens(request);

        return new Completion(
                "chatcmpl-mock-" + Long.toHexString(seedFor(request)),
                request.model(),
                id,
                ChatMessage.assistant(content.toString()),
                new TokenUsage(promptTokens, completionTokens),
                FinishReason.STOP,
                clock.instant());
    }

    /**
     * A crude but stable stand-in for a tokenizer: roughly four characters per token, which is the
     * commonly cited average for English. It does not need to be accurate — it needs to be the same
     * every time, so that quota and cost tests have a fixed input.
     */
    private int estimatePromptTokens(ChatRequest request) {
        int characters = request.messages().stream()
                .mapToInt(message -> message.content().length())
                .sum();
        return Math.max(1, characters / 4);
    }

    private long seedFor(ChatRequest request) {
        CRC32 checksum = new CRC32();
        checksum.update(request.model().getBytes(StandardCharsets.UTF_8));
        for (ChatMessage message : request.messages()) {
            checksum.update(message.role().wireValue().getBytes(StandardCharsets.UTF_8));
            checksum.update(message.content().getBytes(StandardCharsets.UTF_8));
        }
        return properties.seed() * 31 + checksum.getValue();
    }

    @Override
    public Flux<CompletionChunk> stream(ChatRequest request) {
        long seed = seedFor(request);
        String completionId = "chatcmpl-mock-" + Long.toHexString(seed);
        Instant createdAt = clock.instant();
        int total = properties.completionTokens();

        // why Flux.generate and not Flux.create/fromIterable: generate is pull-based — its
        // generator function runs once per unit of downstream demand. That makes this provider
        // structurally incapable of racing ahead of a slow consumer, which is exactly the property
        // the streaming path must have end to end. Flux.create with an unbounded overflow strategy
        // would happily buffer the whole response in memory instead.
        Flux<CompletionChunk> chunks = Flux.generate(
                // Per-subscription state: a retry re-runs this supplier and therefore replays the
                // identical sequence, rather than continuing a shared Random.
                () -> new StreamState(0, contentRandom(seed)), (state, sink) -> {
                    if (properties.failsMidStream() && state.emitted() == properties.failAfterChunks()) {
                        sink.error(new ProviderCallFailed(
                                id, "simulated mid-stream failure after " + state.emitted() + " chunks"));
                        return state;
                    }
                    if (state.emitted() < total) {
                        String word = LOREM[state.random().nextInt(LOREM.length)];
                        sink.next(new CompletionChunk.Delta(
                                completionId,
                                request.model(),
                                id,
                                state.emitted() == 0 ? word : " " + word,
                                createdAt));
                        return state.next();
                    }
                    sink.next(new CompletionChunk.Done(
                            completionId,
                            request.model(),
                            id,
                            FinishReason.STOP,
                            new TokenUsage(estimatePromptTokens(request), total),
                            createdAt));
                    sink.complete();
                    return state.next();
                });

        Flux<CompletionChunk> withFailureRate =
                Flux.defer(() -> failureRandom(seed).nextDouble() < properties.errorRate()
                        ? Flux.<CompletionChunk>error(new ProviderCallFailed(
                                id, "simulated failure (errorRate=" + properties.errorRate() + ")"))
                        : chunks);

        return properties.chunkDelay().isZero()
                ? withFailureRate
                : withFailureRate.delayElements(properties.chunkDelay());
    }

    private record StreamState(int emitted, Random random) {
        StreamState next() {
            return new StreamState(emitted + 1, random);
        }
    }

    @Override
    public Mono<Boolean> isHealthy() {
        // The mock is healthy exactly when it is not configured to fail everything, so a chaos
        // scenario can be expressed entirely in configuration.
        return Mono.just(properties.errorRate() < 1.0);
    }

    /** Exposed so tests can assert the configured latency without reaching into properties. */
    public Duration configuredLatency() {
        return properties.latency();
    }
}

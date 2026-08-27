package io.github.mehmetztrk.llmgateway.adapter.out.provider.mock;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.CRC32;
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

    @Override
    public Mono<Completion> complete(ChatRequest request) {
        long seed = seedFor(request);
        Random random = new Random(seed);

        Mono<Completion> result = Mono.defer(() -> {
            if (random.nextDouble() < properties.errorRate()) {
                return Mono.<Completion>error(
                        new ProviderCallFailed(id, "simulated failure (errorRate=" + properties.errorRate() + ")"));
            }
            return Mono.just(build(request, random));
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

    /** Exposed so tests can assert the configured latency without reaching into properties. */
    public Duration configuredLatency() {
        return properties.latency();
    }
}

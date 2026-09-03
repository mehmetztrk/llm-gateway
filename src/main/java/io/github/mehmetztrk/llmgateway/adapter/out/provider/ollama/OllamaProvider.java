package io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.Role;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.support.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Talks to an Ollama server through its OpenAI-compatible {@code /v1} API.
 *
 * <p>One instance per configured Ollama server, so the same class backs both {@code ollama-primary}
 * and {@code ollama-secondary} — the difference is configuration, not code. That is what makes the
 * M5 failover test exercise a real second provider rather than a mock pretending to be one.
 */
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    /** The sentinel every OpenAI-compatible stream ends with. */
    private static final String DONE_SENTINEL = "[DONE]";

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ProviderId id;
    private final WebClient webClient;
    private final Set<String> models;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final Duration healthTimeout;

    public OllamaProvider(
            ProviderId id, WebClient webClient, Set<String> models, Duration timeout, ObjectMapper objectMapper) {
        this(id, webClient, models, timeout, objectMapper, Duration.ofSeconds(2));
    }

    public OllamaProvider(
            ProviderId id,
            WebClient webClient,
            Set<String> models,
            Duration timeout,
            ObjectMapper objectMapper,
            Duration healthTimeout) {
        this.healthTimeout = healthTimeout;
        this.id = id;
        this.webClient = webClient;
        this.models = Set.copyOf(models);
        this.timeout = timeout;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProviderId id() {
        return id;
    }

    @Override
    public Set<String> supportedModels() {
        return models;
    }

    @Override
    public Mono<Completion> complete(ChatRequest request) {
        OllamaWire.ChatRequest body = toWire(request);

        return webClient
                .post()
                .uri("/v1/chat/completions")
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OllamaWire.ChatResponse.class)
                // why timeout here rather than only on the HTTP client: a server that accepts the
                // connection and then stalls mid-body would otherwise hang this Mono forever. The
                // reactive timeout bounds the whole exchange, not just the connect phase.
                .timeout(timeout)
                .map(response -> toDomain(request, response))
                .onErrorMap(WebClientResponseException.class, this::upstreamStatus)
                .onErrorMap(
                        error -> !(error instanceof ProviderCallFailed),
                        error -> new ProviderCallFailed(id, describe(error), error));
    }

    @Override
    public Flux<CompletionChunk> stream(ChatRequest request) {
        OllamaWire.ChatRequest body = toWire(request, true);

        return Flux.defer(() -> {
                    // Per-subscription bookkeeping, so a retry starts from a clean slate.
                    AtomicBoolean sawTerminal = new AtomicBoolean(false);
                    AtomicReference<String> completionId = new AtomicReference<>("chatcmpl-" + Ids.fast());
                    AtomicReference<String> servedModel = new AtomicReference<>(request.model());

                    Flux<CompletionChunk> body$ = webClient
                            .post()
                            .uri("/v1/chat/completions")
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .bodyValue(body)
                            .retrieve()
                            // Spring's SSE decoder does the framing, so we never hand-parse "data:" lines
                            // or worry about a chunk boundary splitting a frame in half.
                            .bodyToFlux(SSE_TYPE)
                            .mapNotNull(ServerSentEvent::data)
                            .takeUntil(DONE_SENTINEL::equals)
                            .filter(data -> !DONE_SENTINEL.equals(data))
                            .concatMap(data -> toChunks(data, completionId, servedModel, sawTerminal))
                            // why timeout on a Flux means something different: for a Mono it bounds the
                            // whole call, but here it bounds the gap *between* elements. That is the
                            // correct semantic for a stream — a long answer is fine, a stalled one is not.
                            .timeout(timeout);

                    Flux<CompletionChunk> terminalIfMissing = Flux.defer(() -> sawTerminal.get()
                            ? Flux.empty()
                            // Ollama does not always send a finish_reason frame. The port contract
                            // promises the stream ends with a Done element, so we synthesise one rather
                            // than letting the contract be conditionally true.
                            : Flux.just(new CompletionChunk.Done(
                                    completionId.get(),
                                    servedModel.get(),
                                    id,
                                    FinishReason.STOP,
                                    TokenUsage.NONE,
                                    Instant.now())));

                    return body$.concatWith(terminalIfMissing);
                })
                .onErrorMap(WebClientResponseException.class, this::upstreamStatus)
                .onErrorMap(
                        error -> !(error instanceof ProviderCallFailed),
                        error -> new ProviderCallFailed(id, describe(error), error));
    }

    private Flux<CompletionChunk> toChunks(
            String data,
            AtomicReference<String> completionId,
            AtomicReference<String> servedModel,
            AtomicBoolean sawTerminal) {

        OllamaWire.ChatStreamChunk frame;
        try {
            frame = objectMapper.readValue(data, OllamaWire.ChatStreamChunk.class);
        } catch (JsonProcessingException e) {
            return Flux.error(new ProviderCallFailed(id, "unparseable stream frame", e));
        }

        if (frame.id() != null) {
            completionId.set(frame.id());
        }
        if (frame.model() != null) {
            servedModel.set(frame.model());
        }
        if (frame.choices() == null || frame.choices().isEmpty()) {
            return Flux.empty();
        }

        OllamaWire.StreamChoice choice = frame.choices().getFirst();
        Instant createdAt = frame.created() == null ? Instant.now() : Instant.ofEpochSecond(frame.created());
        List<CompletionChunk> chunks = new ArrayList<>(2);

        String content = choice.delta() == null ? null : choice.delta().content();
        if (content != null && !content.isEmpty()) {
            chunks.add(new CompletionChunk.Delta(completionId.get(), servedModel.get(), id, content, createdAt));
        }
        if (choice.finishReason() != null) {
            sawTerminal.set(true);
            TokenUsage usage = frame.usage() == null
                    ? TokenUsage.NONE
                    : new TokenUsage(
                            orZero(frame.usage().promptTokens()),
                            orZero(frame.usage().completionTokens()));
            chunks.add(new CompletionChunk.Done(
                    completionId.get(),
                    servedModel.get(),
                    id,
                    FinishReason.fromWire(choice.finishReason()),
                    usage,
                    createdAt));
        }
        return Flux.fromIterable(chunks);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        // GET /v1/models, not a completion: listing models costs the server nothing, while a
        // generated token costs a GPU. A probe that runs every few seconds forever must be free.
        return webClient
                .get()
                .uri("/v1/models")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .timeout(healthTimeout)
                .onErrorReturn(false);
    }

    private OllamaWire.ChatRequest toWire(ChatRequest request) {
        return toWire(request, false);
    }

    private OllamaWire.ChatRequest toWire(ChatRequest request, boolean stream) {
        List<OllamaWire.Message> messages = request.messages().stream()
                .map(m -> new OllamaWire.Message(m.role().wireValue(), m.content()))
                .toList();
        return new OllamaWire.ChatRequest(
                request.model(), messages, stream, request.maxTokens(), request.temperature());
    }

    private Completion toDomain(ChatRequest request, OllamaWire.ChatResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new ProviderCallFailed(id, "response contained no choices");
        }
        OllamaWire.Choice choice = response.choices().getFirst();
        if (choice.message() == null || choice.message().content() == null) {
            throw new ProviderCallFailed(id, "response choice contained no message content");
        }

        // Ollama reports usage for /v1 calls, but treat it as optional rather than trusting it:
        // a provider that omits usage must not take the gateway down, it must show up as zero and
        // be visible in the ledger as such.
        TokenUsage usage = response.usage() == null
                ? TokenUsage.NONE
                : new TokenUsage(
                        orZero(response.usage().promptTokens()),
                        orZero(response.usage().completionTokens()));
        if (response.usage() == null) {
            log.warn("provider {} returned no usage block for model {}", id, request.model());
        }

        return new Completion(
                response.id() == null ? "chatcmpl-" + Ids.fast() : response.id(),
                response.model() == null ? request.model() : response.model(),
                id,
                new ChatMessage(
                        choice.message().role() == null
                                ? Role.ASSISTANT
                                : Role.fromWire(choice.message().role()),
                        choice.message().content()),
                usage,
                FinishReason.fromWire(choice.finishReason()),
                response.created() == null ? Instant.now() : Instant.ofEpochSecond(response.created()));
    }

    private ProviderCallFailed upstreamStatus(WebClientResponseException error) {
        return new ProviderCallFailed(
                id, "upstream returned HTTP " + error.getStatusCode().value(), error);
    }

    private String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}

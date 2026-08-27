package io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.Role;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
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

    private final ProviderId id;
    private final WebClient webClient;
    private final Set<String> models;
    private final Duration timeout;

    public OllamaProvider(ProviderId id, WebClient webClient, Set<String> models, Duration timeout) {
        this.id = id;
        this.webClient = webClient;
        this.models = Set.copyOf(models);
        this.timeout = timeout;
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

    private OllamaWire.ChatRequest toWire(ChatRequest request) {
        List<OllamaWire.Message> messages = request.messages().stream()
                .map(m -> new OllamaWire.Message(m.role().wireValue(), m.content()))
                .toList();
        return new OllamaWire.ChatRequest(request.model(), messages, false, request.maxTokens(), request.temperature());
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
                response.id() == null ? "chatcmpl-" + UUID.randomUUID() : response.id(),
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

package io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ollama's OpenAI-compatible wire format.
 *
 * <p>why these are not the same records as the ones in {@code adapter/in/web/dto}, despite looking
 * almost identical: adapters are not allowed to depend on each other. If the inbound DTOs were
 * reused here, a change demanded by an inbound API version would silently alter what we send
 * upstream. The duplication is the price of being able to change one side without touching the
 * other, and it disappears the moment a provider's format diverges — which it will.
 */
final class OllamaWire {

    private OllamaWire() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatRequest(
            String model,
            List<Message> messages,
            boolean stream,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature) {}

    public record Message(String role, String content) {}

    /**
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is deliberate: Ollama adds fields between
     * releases, and a gateway that 500s because upstream returned one extra key is worse than
     * useless.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(String id, String model, Long created, List<Choice> choices, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Integer index,
            Message message,
            @JsonProperty("finish_reason") String finishReason) {}

    /** One {@code data:} frame of a streamed response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatStreamChunk(String id, String model, Long created, List<StreamChoice> choices, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamChoice(
            Integer index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason) {}

    /** The incremental payload. Both fields are absent on some frames, hence the nullability. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {}
}

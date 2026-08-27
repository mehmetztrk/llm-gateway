package io.github.mehmetztrk.llmgateway.domain.chat;

import java.util.List;
import java.util.Objects;

/**
 * A completion request in the gateway's own vocabulary, deliberately independent of the OpenAI wire
 * format. Only the parameters the gateway actually reasons about live here; anything a provider
 * passes through untouched does not belong in the domain.
 *
 * <p>Note there is no {@code stream} flag: streaming is a different operation on {@link
 * io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider}, not a variation of this
 * request. That keeps the return type honest — a streamed call cannot accidentally be treated as a
 * single response.
 *
 * @param maxTokens null means "provider default"
 * @param temperature null means "provider default"
 */
public record ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens, Double temperature) {

    public ChatRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(messages, "messages");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive when present");
        }
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be within [0.0, 2.0]");
        }
        // Defensive copy: the caller keeps a mutable reference to the list it passed in.
        messages = List.copyOf(messages);
    }

    public static ChatRequest of(String model, ChatMessage... messages) {
        return new ChatRequest(model, List.of(messages), null, null);
    }
}

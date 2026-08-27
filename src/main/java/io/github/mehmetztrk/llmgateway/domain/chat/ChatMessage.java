package io.github.mehmetztrk.llmgateway.domain.chat;

import java.util.Objects;

/**
 * One turn in a conversation.
 *
 * <p>why a compact constructor: records give you the fields for free but not the invariants. Doing
 * the null check here means every {@code ChatMessage} anywhere in the system is valid by
 * construction, so no downstream code needs a defensive check.
 */
public record ChatMessage(Role role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}

package io.github.mehmetztrk.llmgateway.domain.chat;

import java.util.Locale;

/** Who authored a message in a conversation. */
public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    /** The lowercase form every OpenAI-compatible API uses on the wire. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "system" -> SYSTEM;
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            case "tool", "function" -> TOOL;
            default -> throw new IllegalArgumentException("unknown role: " + value);
        };
    }
}

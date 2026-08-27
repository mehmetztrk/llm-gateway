package io.github.mehmetztrk.llmgateway.domain.chat;

import java.util.Locale;

/** Why generation stopped. */
public enum FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    ERROR;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Unknown values map to {@link #STOP}: a provider inventing a new reason is not a failure. */
    public static FinishReason fromWire(String value) {
        if (value == null) {
            return STOP;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "length" -> LENGTH;
            case "content_filter" -> CONTENT_FILTER;
            case "tool_calls", "function_call" -> TOOL_CALLS;
            case "error" -> ERROR;
            default -> STOP;
        };
    }
}

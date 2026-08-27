package io.github.mehmetztrk.llmgateway.domain.chat;

/**
 * Token accounting for a single call. This is the quantity everything downstream is built on —
 * quotas, rate limits and the cost ledger all derive from it — so it is measured, never estimated.
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0);

    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens);
    }
}

package io.github.mehmetztrk.llmgateway.domain.chat;

import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.time.Instant;
import java.util.Objects;

/**
 * A finished, non-streamed completion.
 *
 * @param model the model the provider actually served, which is not always the alias that was
 *     requested — the usage ledger must record what ran, not what was asked for.
 * @param servedBy which provider produced this; carried through so M5 failover and M7 cost
 *     accounting can attribute the call without re-deriving it.
 */
public record Completion(
        String id,
        String model,
        ProviderId servedBy,
        ChatMessage message,
        TokenUsage usage,
        FinishReason finishReason,
        Instant createdAt) {

    public Completion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(servedBy, "servedBy");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}

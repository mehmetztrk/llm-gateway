package io.github.mehmetztrk.llmgateway.domain.chat;

import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.time.Instant;
import java.util.Objects;

/**
 * One element of a streamed completion.
 *
 * <p>why a sealed interface instead of one record with nullable fields: in a stream, "here is more
 * text" and "that was the last of it, here is the token count" are genuinely different events. A
 * single record would need a nullable {@code finishReason} and a nullable {@code usage}, and every
 * consumer would have to remember which combination means what. Splitting them makes the illegal
 * states unrepresentable and lets the SSE writer switch exhaustively.
 */
public sealed interface CompletionChunk {

    String id();

    String model();

    ProviderId servedBy();

    Instant createdAt();

    /** Incremental content. {@code content} may be empty but is never null. */
    record Delta(String id, String model, ProviderId servedBy, String content, Instant createdAt)
            implements CompletionChunk {

        public Delta {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(servedBy, "servedBy");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * Terminal element: generation finished normally.
     *
     * @param usage may be {@link TokenUsage#NONE} when the provider does not report usage on a
     *     streamed call — which is common, and is exactly why the usage ledger records what was
     *     measured rather than what was hoped for.
     */
    record Done(
            String id,
            String model,
            ProviderId servedBy,
            FinishReason finishReason,
            TokenUsage usage,
            Instant createdAt)
            implements CompletionChunk {

        public Done {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(servedBy, "servedBy");
            Objects.requireNonNull(finishReason, "finishReason");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}

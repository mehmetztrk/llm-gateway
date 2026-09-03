package io.github.mehmetztrk.llmgateway.domain.cache;

import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import java.util.Objects;

/**
 * A completion served from cache, together with how it was found.
 *
 * <p>The origin is carried rather than inferred because it is the number the whole feature is
 * judged on: an exact hit and a semantic hit have very different implications for correctness, and
 * a cache-hit ratio that lumps them together hides the interesting half.
 */
public record CachedCompletion(Completion completion, Origin origin, double similarity) {

    public enum Origin {
        /** Byte-identical prompt. Always safe. */
        EXACT,
        /** A different prompt that embedded close enough. Safe only if the threshold is right. */
        SEMANTIC
    }

    public CachedCompletion {
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(origin, "origin");
    }

    public static CachedCompletion exact(Completion completion) {
        // An exact match has no meaningful distance; 1.0 says "identical" without pretending a
        // similarity was computed.
        return new CachedCompletion(completion, Origin.EXACT, 1.0);
    }

    public static CachedCompletion semantic(Completion completion, double similarity) {
        return new CachedCompletion(completion, Origin.SEMANTIC, similarity);
    }
}

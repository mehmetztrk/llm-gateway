package io.github.mehmetztrk.llmgateway.domain.cache;

/**
 * Where a response came from, reported back to the client.
 *
 * <p>Exposed deliberately. A tenant billed by tokens is entitled to know when it was served from
 * cache and did not consume any, and an operator debugging "why is this answer stale" should not
 * have to read logs to find out.
 */
public enum CacheStatus {
    MISS,
    EXACT_HIT,
    SEMANTIC_HIT;

    public boolean isHit() {
        return this != MISS;
    }

    public String wireValue() {
        return switch (this) {
            case MISS -> "miss";
            case EXACT_HIT -> "hit-exact";
            case SEMANTIC_HIT -> "hit-semantic";
        };
    }
}

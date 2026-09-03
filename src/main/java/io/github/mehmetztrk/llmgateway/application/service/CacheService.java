package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.out.ResponseCache;
import io.github.mehmetztrk.llmgateway.domain.cache.CachedCompletion;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.util.concurrent.atomic.LongAdder;
import reactor.core.publisher.Mono;

/**
 * Exact first, then semantic.
 *
 * <p>The order is not a preference, it is a correctness argument. An exact hit is unconditionally
 * right: same tenant, same model, same prompt, same parameters. A semantic hit is right only if the
 * threshold is, so it is consulted second and only when the cheap, certain answer is unavailable.
 * The exact lookup is also an order of magnitude cheaper — one key against a vector scan — so
 * trying it first costs nothing when it misses.
 *
 * <p>Counters are kept here rather than derived from logs so the cache-hit ratio in BENCHMARKS.md
 * is a measurement rather than an estimate.
 */
public class CacheService {

    private final ResponseCache exact;
    private final ResponseCache semantic;
    private final boolean semanticEnabled;

    private final LongAdder lookups = new LongAdder();
    private final LongAdder exactHits = new LongAdder();
    private final LongAdder semanticHits = new LongAdder();
    private final LongAdder tokensSaved = new LongAdder();

    public CacheService(ResponseCache exact, ResponseCache semantic, boolean semanticEnabled) {
        this.exact = exact;
        this.semantic = semantic;
        this.semanticEnabled = semanticEnabled;
    }

    public Mono<CachedCompletion> lookup(TenantId tenant, ChatRequest request) {
        lookups.increment();

        Mono<CachedCompletion> exactLookup = exact.lookup(tenant, request).doOnNext(hit -> {
            exactHits.increment();
            tokensSaved.add(hit.completion().usage().totalTokens());
        });

        if (!semanticEnabled) {
            return exactLookup;
        }

        return exactLookup.switchIfEmpty(semantic.lookup(tenant, request).doOnNext(hit -> {
            semanticHits.increment();
            tokensSaved.add(hit.completion().usage().totalTokens());
        }));
    }

    /**
     * Write to both layers.
     *
     * <p>Both, not one: the exact cache answers the repeat of this precise prompt in microseconds,
     * and the semantic one answers the paraphrase. Storing only in the semantic layer would make
     * every repeat pay for an embedding and a vector scan to learn something a hash lookup knew.
     */
    public Mono<Void> store(TenantId tenant, ChatRequest request, Completion completion) {
        Mono<Void> writes = exact.store(tenant, request, completion);
        if (semanticEnabled) {
            writes = writes.then(semantic.store(tenant, request, completion));
        }
        // Never fails the caller: the completion is already produced, and failing a successful
        // request because it could not be cached would be absurd.
        return writes.onErrorResume(error -> Mono.empty());
    }

    public Stats stats() {
        long total = lookups.sum();
        long hits = exactHits.sum() + semanticHits.sum();
        return new Stats(
                total,
                exactHits.sum(),
                semanticHits.sum(),
                tokensSaved.sum(),
                total == 0 ? 0.0 : (double) hits / total);
    }

    /** @param hitRatio hits divided by lookups; zero when nothing has been looked up yet */
    public record Stats(long lookups, long exactHits, long semanticHits, long tokensSaved, double hitRatio) {}
}

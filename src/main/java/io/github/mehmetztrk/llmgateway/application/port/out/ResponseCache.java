package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.cache.CachedCompletion;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * A cache of completed responses, scoped to one tenant.
 *
 * <p>Every method takes the tenant explicitly. It could have been folded into a key built by the
 * caller, but making it a parameter means no implementation can accidentally serve across tenants
 * and no future implementation can forget to scope itself — the compiler asks for it.
 *
 * <p><b>Implementations must fail open.</b> A cache that cannot answer must behave exactly like a
 * miss, because a cache exists to avoid work and its outage should cost latency rather than
 * availability. That is safe here only because rate limiting and quota checks run <em>before</em>
 * the cache is consulted; see ADR-0004.
 */
public interface ResponseCache {

    /** Empty means miss, including when the cache itself is unavailable. */
    Mono<CachedCompletion> lookup(TenantId tenant, ChatRequest request);

    /**
     * Store a response.
     *
     * <p>Never fails the caller: the completion has already been produced and returning an error
     * because it could not be cached would turn a successful request into a failed one.
     */
    Mono<Void> store(TenantId tenant, ChatRequest request, Completion completion);
}

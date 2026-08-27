package io.github.mehmetztrk.llmgateway.application.port.in;

import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the gateway offers the outside world for a completion.
 *
 * <p>why the caller is an explicit parameter rather than ambient context: every policy decision
 * downstream — allow-list, rate limits, quotas, cache scope in M6, ledger attribution in M7 — is
 * keyed by tenant. Making tenancy a required argument means a new code path cannot silently omit
 * it; it will not compile. Reading it from a reactive context deeper down would compile fine and
 * fail at runtime, in production, as a cross-tenant leak.
 */
public interface ChatCompletionUseCase {

    Mono<GatewayResult<Completion>> complete(AuthenticatedCaller caller, ChatRequest request);

    /**
     * The same request, delivered incrementally.
     *
     * <p>Returns a {@code Mono} of the result rather than a bare {@code Flux} because admission
     * happens before the first chunk: the outer Mono completes once the request has been allowed,
     * which is exactly when the response headers must be written.
     */
    Mono<GatewayResult<Flux<CompletionChunk>>> stream(AuthenticatedCaller caller, ChatRequest request);
}

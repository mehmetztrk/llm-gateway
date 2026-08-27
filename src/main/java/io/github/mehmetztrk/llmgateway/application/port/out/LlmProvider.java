package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The single seam between the gateway and anything that can generate a completion.
 *
 * <p>Everything the gateway does — routing, failover, caching, cost accounting — is written against
 * this interface, which is what makes "add a paid provider" a configuration change rather than a
 * refactor. Implementations live in {@code adapter/out/provider} and are the only code allowed to
 * know a vendor's wire format.
 *
 * <p>why {@code Mono<Completion>} and not {@code Completion}: the whole request path is
 * non-blocking, so a provider must hand back a promise rather than occupy a thread while the model
 * thinks. Streaming arrives in M2 as a separate {@code Flux}-returning method rather than a flag on
 * this one — see {@link ChatRequest}.
 */
public interface LlmProvider {

    /** Stable identity used in logs, metrics, spans and ledger rows. */
    ProviderId id();

    /** Model names this provider is configured to serve. */
    Set<String> supportedModels();

    default boolean supports(String model) {
        return supportedModels().contains(model);
    }

    /**
     * Generate one complete response.
     *
     * <p>Implementations must signal failure as {@link
     * io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed} rather than leaking a
     * transport exception, so callers can reason about failure without knowing the vendor.
     */
    Mono<Completion> complete(ChatRequest request);

    /**
     * Generate the same response incrementally.
     *
     * <p>why a separate method rather than a {@code stream} flag on {@link ChatRequest}: the return
     * types differ, so a flag would force every caller to handle a value that may or may not be
     * there. Two methods let the compiler keep the two paths apart.
     *
     * <p>The returned {@link Flux} must be <b>demand-driven</b>: implementations may not produce
     * elements faster than they are requested. A provider that pushes eagerly turns a slow client
     * into unbounded memory growth inside the gateway — the failure mode this milestone exists to
     * prevent.
     *
     * <p>The stream ends either with a {@link CompletionChunk.Done} element followed by completion,
     * or with an error signal carrying {@link
     * io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed}. It never simply stops.
     */
    Flux<CompletionChunk> stream(ChatRequest request);
}

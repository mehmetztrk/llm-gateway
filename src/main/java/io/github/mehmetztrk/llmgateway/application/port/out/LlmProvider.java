package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.util.Set;
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
}

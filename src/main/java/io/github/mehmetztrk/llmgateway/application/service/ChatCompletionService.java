package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.GatewayException;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The request pipeline.
 *
 * <p>Order matters and is deliberate: the tenant's policy is checked before a provider is chosen,
 * so a model a tenant may not use is rejected without a single upstream call. Rate limiting,
 * quotas, caching and failover slot in around this in later milestones.
 */
public class ChatCompletionService implements ChatCompletionUseCase {

    private final ProviderRegistry registry;

    public ChatCompletionService(ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<Completion> complete(AuthenticatedCaller caller, ChatRequest request) {
        // why defer: both requireModelAllowed and requireProviderFor throw, and without defer those
        // throws would escape synchronously at assembly time instead of arriving as onError
        // signals. Every failure then reaches the caller through one path.
        return Mono.defer(() -> {
            caller.tenant().requireModelAllowed(request.model());
            LlmProvider provider = registry.requireProviderFor(request.model());
            return provider.complete(request).onErrorMap(normaliseFrom(provider));
        });
    }

    @Override
    public Flux<CompletionChunk> stream(AuthenticatedCaller caller, ChatRequest request) {
        return Flux.defer(() -> {
            caller.tenant().requireModelAllowed(request.model());
            LlmProvider provider = registry.requireProviderFor(request.model());
            return provider.stream(request).onErrorMap(normaliseFrom(provider));
        });
    }

    /**
     * A provider that leaks a transport exception is a bug in that adapter, but it must not reach
     * the client as a 500. Anything not already in the domain vocabulary is normalised here.
     */
    private java.util.function.Function<Throwable, Throwable> normaliseFrom(LlmProvider provider) {
        return error -> error instanceof GatewayException
                ? error
                : new ProviderCallFailed(
                        provider.id(), "unexpected error: " + error.getClass().getSimpleName(), error);
    }
}

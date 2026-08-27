package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.GatewayException;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The request pipeline. In M1 it is only "pick a provider and call it"; auth, rate limiting,
 * quotas, caching and failover are inserted here in later milestones, in that order.
 */
public class ChatCompletionService implements ChatCompletionUseCase {

    private final ProviderRegistry registry;

    public ChatCompletionService(ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<Completion> complete(ChatRequest request) {
        // why defer: requireProviderFor throws, and a throw during assembly escapes the reactive
        // chain as a synchronous exception instead of an onError signal. Mono.defer moves it to
        // subscription time so every failure reaches the caller through one path.
        return Mono.defer(() -> {
            LlmProvider provider = registry.requireProviderFor(request.model());
            return provider.complete(request)
                    // A provider that leaks a transport exception is a bug in that adapter, but it
                    // must not reach the client as a 500. Anything not already expressed in the
                    // domain vocabulary is normalised here.
                    .onErrorMap(
                            error -> !(error instanceof GatewayException),
                            error -> new ProviderCallFailed(
                                    provider.id(),
                                    "unexpected error: " + error.getClass().getSimpleName(),
                                    error));
        });
    }

    @Override
    public Flux<CompletionChunk> stream(ChatRequest request) {
        // Flux.defer for the same reason Mono.defer is used above: provider lookup can throw, and
        // that throw must arrive as an onError signal rather than escaping at assembly time.
        return Flux.defer(() -> {
            LlmProvider provider = registry.requireProviderFor(request.model());
            return provider.stream(request)
                    .onErrorMap(
                            error -> !(error instanceof GatewayException),
                            error -> new ProviderCallFailed(
                                    provider.id(),
                                    "unexpected error: " + error.getClass().getSimpleName(),
                                    error));
        });
    }
}

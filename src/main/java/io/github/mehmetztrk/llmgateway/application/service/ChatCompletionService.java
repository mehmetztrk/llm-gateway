package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.application.port.in.GatewayResult;
import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.GatewayException;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The request pipeline.
 *
 * <p>The order is deliberate and each step is cheaper than the one after it: policy, then admission,
 * then the provider. A model a tenant may not use costs no Redis round trip; a request over its
 * limit costs no provider call.
 *
 * <p>Caching slots in between admission and routing in M6; the usage ledger hangs off settlement in
 * M7.
 */
public class ChatCompletionService implements ChatCompletionUseCase {

    private final ProviderRegistry registry;
    private final RateLimitService limits;

    public ChatCompletionService(ProviderRegistry registry, RateLimitService limits) {
        this.registry = registry;
        this.limits = limits;
    }

    @Override
    public Mono<GatewayResult<Completion>> complete(AuthenticatedCaller caller, ChatRequest request) {
        return Mono.defer(() -> {
            caller.tenant().requireModelAllowed(request.model());
            LlmProvider provider = registry.requireProviderFor(request.model());
            long estimate = estimateTokens(request);

            return limits.admit(caller, estimate)
                    .flatMap(admission -> provider.complete(request)
                            .onErrorMap(normaliseFrom(provider))
                            .flatMap(completion -> limits.settle(
                                            caller, estimate, completion.usage().totalTokens())
                                    .map(quota -> new GatewayResult<>(
                                            completion, admission.requests(), admission.tokens(), quota))));
        });
    }

    @Override
    public Mono<GatewayResult<Flux<CompletionChunk>>> stream(AuthenticatedCaller caller, ChatRequest request) {
        return Mono.defer(() -> {
            caller.tenant().requireModelAllowed(request.model());
            LlmProvider provider = registry.requireProviderFor(request.model());
            long estimate = estimateTokens(request);

            return limits.admit(caller, estimate).map(admission -> {
                Flux<CompletionChunk> chunks = provider.stream(request)
                        .onErrorMap(normaliseFrom(provider))
                        // Settlement is attached to the terminal chunk rather than to the end of
                        // the stream: a stream that fails halfway still consumed whatever it
                        // produced, and doOnComplete would skip exactly that case.
                        .concatMap(chunk -> chunk instanceof CompletionChunk.Done done
                                ? limits.settle(caller, estimate, done.usage().totalTokens())
                                        .thenReturn(chunk)
                                : Mono.just(chunk));

                return new GatewayResult<>(chunks, admission.requests(), admission.tokens(), admission.quota());
            });
        });
    }

    /**
     * A conservative stand-in for the prompt's token count, used only for admission.
     *
     * <p>Roughly four characters per token, the commonly cited English average. It does not need to
     * be exact — it needs to be cheap and never wildly low, because the difference is settled
     * against the real count as soon as the provider reports it. Tokenising properly here would
     * mean loading a model-specific tokenizer on the hot path to answer a question the provider
     * answers for free a moment later.
     */
    private long estimateTokens(ChatRequest request) {
        long characters = request.messages().stream()
                .mapToLong(message -> message.content().length())
                .sum();
        return Math.max(1, characters / 4);
    }

    /**
     * A provider that leaks a transport exception is a bug in that adapter, but it must not reach
     * the client as a 500. Anything not already in the domain vocabulary is normalised here.
     */
    private Function<Throwable, Throwable> normaliseFrom(LlmProvider provider) {
        return error -> error instanceof GatewayException
                ? error
                : new ProviderCallFailed(
                        provider.id(), "unexpected error: " + error.getClass().getSimpleName(), error);
    }
}

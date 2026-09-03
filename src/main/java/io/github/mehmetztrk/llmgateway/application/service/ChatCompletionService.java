package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.application.port.in.GatewayResult;
import io.github.mehmetztrk.llmgateway.domain.cache.CacheStatus;
import io.github.mehmetztrk.llmgateway.domain.cache.CachedCompletion;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The request pipeline.
 *
 * <p>The order is deliberate and each step is cheaper than the one after it: policy, admission,
 * cache, routing, provider. A model a tenant may not use costs no Redis round trip; a request over
 * its limit costs no cache lookup; a cache hit costs no provider call.
 *
 * <p><b>Why the cache sits after admission and not before.</b> Putting it first would make a cached
 * request free of rate limiting, so a tenant could hammer the gateway without limit as long as it
 * repeated itself — and an outage of the cache would then also be an outage of admission control.
 * Checking limits first is also what makes the cache safe to fail open (ADR-0004).
 */
public class ChatCompletionService implements ChatCompletionUseCase {

    private final RoutingService routing;
    private final FailoverExecutor failover;
    private final RateLimitService limits;
    private final CacheService cache;
    private final UsageRecorder usage;

    public ChatCompletionService(
            RoutingService routing,
            FailoverExecutor failover,
            RateLimitService limits,
            CacheService cache,
            UsageRecorder usage) {
        this.routing = routing;
        this.failover = failover;
        this.limits = limits;
        this.cache = cache;
        this.usage = usage;
    }

    @Override
    public Mono<GatewayResult<Completion>> complete(AuthenticatedCaller caller, ChatRequest request) {
        return Mono.defer(() -> {
            caller.tenant().requireModelAllowed(request.model());
            List<RouteTarget> targets = routing.resolve(request.model());
            long estimate = estimateTokens(request);

            long startedAt = System.nanoTime();

            return limits.admit(caller, estimate)
                    .flatMap(admission -> cache.lookup(caller.tenantId(), request)
                            .map(hit -> {
                                usage.record(caller, hit.completion(), statusOf(hit), elapsedSince(startedAt), false);
                                return new GatewayResult<>(
                                        hit.completion(),
                                        admission.requests(),
                                        admission.tokens(),
                                        admission.quota(),
                                        statusOf(hit));
                            })
                            .switchIfEmpty(Mono.defer(() -> failover.complete(targets, request)
                                    .flatMap(completion -> cache.store(caller.tenantId(), request, completion)
                                            .then(limits.settle(
                                                    caller,
                                                    estimate,
                                                    completion.usage().totalTokens()))
                                            .map(quota -> {
                                                usage.record(
                                                        caller,
                                                        completion,
                                                        CacheStatus.MISS,
                                                        elapsedSince(startedAt),
                                                        false);
                                                return new GatewayResult<>(
                                                        completion,
                                                        admission.requests(),
                                                        admission.tokens(),
                                                        quota,
                                                        CacheStatus.MISS);
                                            })))));
        });
    }

    @Override
    public Mono<GatewayResult<Flux<CompletionChunk>>> stream(AuthenticatedCaller caller, ChatRequest request) {
        return Mono.defer(() -> {
            caller.tenant().requireModelAllowed(request.model());
            List<RouteTarget> targets = routing.resolve(request.model());
            long estimate = estimateTokens(request);

            long startedAt = System.nanoTime();

            return limits.admit(caller, estimate)
                    .flatMap(admission -> cache.lookup(caller.tenantId(), request)
                            .map(hit -> {
                                usage.record(caller, hit.completion(), statusOf(hit), elapsedSince(startedAt), true);
                                return new GatewayResult<>(
                                        replay(hit.completion()),
                                        admission.requests(),
                                        admission.tokens(),
                                        admission.quota(),
                                        statusOf(hit));
                            })
                            .switchIfEmpty(Mono.fromSupplier(() -> {
                                Flux<CompletionChunk> chunks = failover.stream(targets, request)
                                        // Settlement is attached to the terminal chunk rather than to the
                                        // end of the stream: a stream that fails halfway still consumed
                                        // whatever it produced, and doOnComplete would skip that case.
                                        // The terminal chunk is where the real token count first
                                        // exists, so it is where both settlement and the ledger
                                        // row belong. Hanging them off stream completion instead
                                        // would skip the case that matters most: a stream that
                                        // failed partway still consumed what it produced.
                                        .concatMap(chunk -> chunk instanceof CompletionChunk.Done done
                                                ? limits.settle(
                                                                caller,
                                                                estimate,
                                                                done.usage().totalTokens())
                                                        .doOnSuccess(ignored -> usage.record(
                                                                caller,
                                                                toCompletion(done),
                                                                CacheStatus.MISS,
                                                                elapsedSince(startedAt),
                                                                true))
                                                        .thenReturn(chunk)
                                                : Mono.just(chunk));

                                return new GatewayResult<>(
                                        chunks,
                                        admission.requests(),
                                        admission.tokens(),
                                        admission.quota(),
                                        CacheStatus.MISS);
                            })));
        });
    }

    /**
     * Replays a cached completion as a stream.
     *
     * <p>A client that asked for a stream gets a stream, cached or not. Returning a single blob to
     * a streaming client because the answer happened to be cached would make cache hits visibly
     * different from misses, and every SDK's incremental-render path would break on exactly the
     * requests that were supposed to be fastest.
     *
     * <p>Word-by-word rather than the whole string in one chunk: the shape of the stream should not
     * betray where the answer came from either.
     */
    private Flux<CompletionChunk> replay(Completion completion) {
        String[] words = completion.message().content().split(" ");

        Flux<CompletionChunk> deltas = Flux.range(0, words.length)
                .map(index -> (CompletionChunk) new CompletionChunk.Delta(
                        completion.id(),
                        completion.model(),
                        completion.servedBy(),
                        index == 0 ? words[index] : " " + words[index],
                        completion.createdAt()));

        return deltas.concatWith(Flux.just(new CompletionChunk.Done(
                completion.id(),
                completion.model(),
                completion.servedBy(),
                completion.finishReason(),
                completion.usage(),
                completion.createdAt())));
    }

    /**
     * A cache hit deliberately does <b>not</b> charge tokens to the quota: no tokens were spent, and
     * charging for them would erase the saving the cache exists to produce. The request itself was
     * already charged against the rate limiter at admission, so repetition is still bounded.
     */
    private CacheStatus statusOf(CachedCompletion hit) {
        return hit.origin() == CachedCompletion.Origin.EXACT ? CacheStatus.EXACT_HIT : CacheStatus.SEMANTIC_HIT;
    }

    /**
     * A terminal chunk carries everything a ledger row needs except the assistant's text, which the
     * ledger does not store — prompts and completions are never persisted here, by design.
     */
    private Completion toCompletion(CompletionChunk.Done done) {
        return new Completion(
                done.id(),
                done.model(),
                done.servedBy(),
                io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage.assistant(""),
                done.usage(),
                done.finishReason(),
                done.createdAt());
    }

    private java.time.Duration elapsedSince(long startedNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    /**
     * A conservative stand-in for the prompt's token count, used only for admission.
     *
     * <p>Roughly four characters per token, the commonly cited English average. It does not need to
     * be exact — it needs to be cheap and never wildly low, because the difference is settled
     * against the real count as soon as the provider reports it.
     */
    private long estimateTokens(ChatRequest request) {
        long characters = request.messages().stream()
                .mapToLong(message -> message.content().length())
                .sum();
        return Math.max(1, characters / 4);
    }
}

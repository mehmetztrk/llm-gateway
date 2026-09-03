package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.application.port.out.ProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.NoProviderAvailable;
import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tries each candidate in turn until one answers.
 *
 * <p><b>The streaming case is the interesting one.</b> A non-streamed call either produces a
 * completion or does not, so failing over is simply "try the next one". A stream that has already
 * delivered three chunks cannot be retried anywhere: the client holds part of an answer, and
 * starting a second provider would splice two different responses together and hand the result over
 * as though it were one. So failover is allowed strictly <em>before the first chunk</em>, and a
 * failure after that propagates to the client as a mid-stream error.
 *
 * <p>That distinction is enforced with a per-subscription flag rather than a clever operator,
 * because it has to be obvious to whoever reads it next.
 */
public class FailoverExecutor {

    private static final Logger log = LoggerFactory.getLogger(FailoverExecutor.class);

    private final ProviderRegistry providers;
    private final ProviderHealthRegistry health;

    public FailoverExecutor(ProviderRegistry providers, ProviderHealthRegistry health) {
        this.providers = providers;
        this.health = health;
    }

    public Mono<Completion> complete(List<RouteTarget> targets, ChatRequest request) {
        AtomicReference<Throwable> lastFailure = new AtomicReference<>();

        return Flux.fromIterable(targets)
                .concatMap(target -> callOne(target, request)
                        .flux()
                        // A failure becomes an empty result, so concatMap moves on to the next
                        // candidate. next() below then takes whichever one succeeds first.
                        .onErrorResume(error -> {
                            recordFailure(target, error, lastFailure);
                            return Flux.empty();
                        }))
                .next()
                .switchIfEmpty(Mono.defer(
                        () -> Mono.error(new NoProviderAvailable(request.model(), targets, lastFailure.get()))));
    }

    public Flux<CompletionChunk> stream(List<RouteTarget> targets, ChatRequest request) {
        AtomicReference<Throwable> lastFailure = new AtomicReference<>();

        return Flux.fromIterable(targets)
                .concatMap(target -> streamOne(target, request, lastFailure))
                // switchIfEmpty covers the case where every candidate failed before emitting.
                .switchIfEmpty(Flux.defer(
                        () -> Flux.error(new NoProviderAvailable(request.model(), targets, lastFailure.get()))));
    }

    private Mono<Completion> callOne(RouteTarget target, ChatRequest request) {
        LlmProvider provider = providers
                .byId(target.provider())
                .orElseThrow(() -> new IllegalStateException("route names unknown provider " + target.provider()));

        return provider.complete(request.withModel(target.model())).doOnSuccess(completion -> {
            health.recordSuccess(target.provider());
            log.debug("served {} via {}", request.model(), target);
        });
    }

    private Flux<CompletionChunk> streamOne(
            RouteTarget target, ChatRequest request, AtomicReference<Throwable> lastFailure) {
        LlmProvider provider = providers
                .byId(target.provider())
                .orElseThrow(() -> new IllegalStateException("route names unknown provider " + target.provider()));

        AtomicBoolean produced = new AtomicBoolean(false);

        return provider.stream(request.withModel(target.model()))
                .doOnNext(chunk -> {
                    if (produced.compareAndSet(false, true)) {
                        health.recordSuccess(target.provider());
                    }
                })
                .onErrorResume(error -> {
                    if (produced.get()) {
                        // Too late to fail over: the client already holds part of this answer.
                        // Splicing a second provider's output onto it would be worse than failing.
                        log.warn("stream from {} failed after it had started: {}", target, error.toString());
                        return Flux.error(error);
                    }
                    recordFailure(target, error, lastFailure);
                    return Flux.empty();
                });
    }

    private void recordFailure(RouteTarget target, Throwable error, AtomicReference<Throwable> lastFailure) {
        health.recordFailure(target.provider());
        lastFailure.set(error);
        log.warn("provider {} failed for model {}: {}", target.provider(), target.model(), error.toString());
    }
}

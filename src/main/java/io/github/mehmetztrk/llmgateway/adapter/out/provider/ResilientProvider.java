package io.github.mehmetztrk.llmgateway.adapter.out.provider;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wraps a provider in a circuit breaker.
 *
 * <p><b>Why a breaker at all, when there is already failover.</b> Failover handles a request that
 * fails; a breaker handles the thousandth request that is going to fail. Without one, every single
 * request keeps paying the full timeout against a dead provider before moving on — so an outage of
 * the primary turns into latency on every request rather than a switch to the secondary. The
 * breaker makes the second and subsequent failures free.
 *
 * <p><b>Streaming is guarded too, but only up to the first chunk.</b> Once the response has started
 * flowing there is nothing to fail over to, and counting a mid-stream failure against the breaker
 * would let one long broken stream trip a provider that is otherwise fine.
 *
 * <p>{@link CallNotPermittedException} — the breaker refusing outright — is translated into
 * {@link ProviderCallFailed} so that routing treats "open circuit" exactly like any other provider
 * failure and moves on. Leaking a Resilience4j type here would make the routing layer depend on the
 * resilience library it should know nothing about.
 */
public class ResilientProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final CircuitBreaker circuitBreaker;

    public ResilientProvider(LlmProvider delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ProviderId id() {
        return delegate.id();
    }

    @Override
    public Set<String> supportedModels() {
        return delegate.supportedModels();
    }

    @Override
    public Mono<Completion> complete(ChatRequest request) {
        return delegate.complete(request)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(CallNotPermittedException.class, this::circuitOpen);
    }

    @Override
    public Flux<CompletionChunk> stream(ChatRequest request) {
        return delegate.stream(request)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(CallNotPermittedException.class, this::circuitOpen);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        // Probes bypass the breaker on purpose. An open breaker means "stop sending traffic", not
        // "stop checking" — if probes were refused too, nothing would ever observe the recovery
        // that closes the breaker again.
        return delegate.isHealthy();
    }

    /** Exposed so the health endpoint and metrics can report breaker state without casting. */
    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private ProviderCallFailed circuitOpen(CallNotPermittedException exception) {
        return new ProviderCallFailed(id(), "circuit breaker is open", exception);
    }
}

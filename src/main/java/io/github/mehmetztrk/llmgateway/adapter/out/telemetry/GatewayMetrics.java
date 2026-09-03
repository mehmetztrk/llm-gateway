package io.github.mehmetztrk.llmgateway.adapter.out.telemetry;

import io.github.mehmetztrk.llmgateway.domain.cache.CacheStatus;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * The gateway's own metrics.
 *
 * <p><b>What is deliberately not a label.</b> Tenant id is omitted from every metric here. A
 * Prometheus label with unbounded cardinality creates one time series per value, so a few thousand
 * tenants multiplied across a handful of metrics is a cardinality explosion that takes the
 * monitoring system down before it tells you anything. Per-tenant numbers belong in the usage
 * ledger, which is built for exactly that query and is not held in memory.
 *
 * <p>Model and provider <em>are</em> labels: both are bounded by configuration, and both are the
 * dimensions an operator actually slices by.
 */
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** End-to-end time the gateway spent on a request, tagged by outcome. */
    public void recordRequest(String model, ProviderId provider, CacheStatus cache, Duration latency) {
        Timer.builder("llmgw.request.duration")
                .description("Time from request received to response ready")
                .tag("model", model)
                .tag("provider", provider.value())
                .tag("cache", cache.wireValue())
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(latency);
    }

    /**
     * Tokens are counted separately from requests because they are the unit that costs money, and
     * a spike in tokens with flat request count is a completely different incident from the
     * reverse.
     */
    public void recordTokens(String model, TokenUsage usage) {
        Counter.builder("llmgw.tokens.total")
                .tag("model", model)
                .tag("direction", "input")
                .register(registry)
                .increment(usage.promptTokens());
        Counter.builder("llmgw.tokens.total")
                .tag("model", model)
                .tag("direction", "output")
                .register(registry)
                .increment(usage.completionTokens());
    }

    public void recordCache(CacheStatus status) {
        Counter.builder("llmgw.cache.lookups.total")
                .description("Cache lookups by outcome; the hit ratio is derived from this")
                .tag("result", status.wireValue())
                .register(registry)
                .increment();
    }

    public void recordRejection(String reason) {
        // Rejections are counted by reason rather than lumped into an error rate: "over quota" and
        // "provider down" are both refusals and require opposite responses from an operator.
        Counter.builder("llmgw.requests.rejected.total")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordFailover(ProviderId from, ProviderId to) {
        Counter.builder("llmgw.failover.total")
                .tag("from", from.value())
                .tag("to", to.value())
                .register(registry)
                .increment();
    }
}

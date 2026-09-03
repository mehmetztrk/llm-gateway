package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.domain.cache.CacheStatus;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.support.Ids;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.usage.Money;
import io.github.mehmetztrk.llmgateway.domain.usage.PriceTable;
import io.github.mehmetztrk.llmgateway.domain.usage.UsageRecord;
import java.time.Clock;
import java.time.Duration;

/**
 * Turns a finished request into a ledger row.
 *
 * <p>Separated from the pipeline so that the pipeline reads as a sequence of decisions rather than
 * a sequence of decisions interleaved with bookkeeping. Everything here is fire-and-forget: the
 * completion has already been returned by the time this runs.
 */
public class UsageRecorder {

    private final UsageLedger ledger;
    private final PriceTable prices;
    private final Clock clock;
    private final MetricsSink metrics;

    public UsageRecorder(UsageLedger ledger, PriceTable prices, Clock clock) {
        this(ledger, prices, clock, MetricsSink.NONE);
    }

    public UsageRecorder(UsageLedger ledger, PriceTable prices, Clock clock, MetricsSink metrics) {
        this.ledger = ledger;
        this.prices = prices;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * The metrics side of recording, as a port.
     *
     * <p>Kept as an interface declared here rather than a Micrometer dependency, because the
     * application layer may not import a framework — and because a test that asserts on ledger rows
     * should not have to stand up a meter registry to do it.
     */
    public interface MetricsSink {
        MetricsSink NONE = (model, provider, cache, latency, usage) -> {};

        void record(
                String model,
                io.github.mehmetztrk.llmgateway.domain.routing.ProviderId provider,
                CacheStatus cache,
                Duration latency,
                io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage usage);
    }

    public void record(
            AuthenticatedCaller caller,
            Completion completion,
            CacheStatus cacheStatus,
            Duration latency,
            boolean streamed) {

        // A cache hit spent no tokens upstream, so it costs nothing. Recording it at full price
        // would make the ledger disagree with the invoice and erase the saving on paper.
        Money cost = cacheStatus.isHit()
                ? Money.zero(prices.currency())
                : prices.costOf(completion.model(), completion.usage());

        metrics.record(completion.model(), completion.servedBy(), cacheStatus, latency, completion.usage());

        ledger.record(new UsageRecord(
                Ids.fast(),
                caller.tenantId(),
                caller.apiKeyId(),
                completion.model(),
                completion.servedBy(),
                completion.usage(),
                cost,
                latency,
                cacheStatus,
                streamed,
                UsageRecord.Outcome.SUCCESS,
                clock.instant()));
    }
}

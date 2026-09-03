package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.domain.cache.CacheStatus;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.usage.Money;
import io.github.mehmetztrk.llmgateway.domain.usage.PriceTable;
import io.github.mehmetztrk.llmgateway.domain.usage.UsageRecord;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

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

    public UsageRecorder(UsageLedger ledger, PriceTable prices, Clock clock) {
        this.ledger = ledger;
        this.prices = prices;
        this.clock = clock;
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

        ledger.record(new UsageRecord(
                UUID.randomUUID(),
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

package io.github.mehmetztrk.llmgateway.domain.error;

import io.github.mehmetztrk.llmgateway.domain.limits.QuotaSnapshot;

/**
 * The tenant has spent its monthly budget. Maps to HTTP 429 with OpenAI's
 * {@code insufficient_quota} code — the same status as a rate limit but a different code, because
 * the remedies are opposite: a rate limit clears by waiting, a quota does not.
 *
 * <p>Deliberately <b>not</b> given a {@code Retry-After}. Telling a client to retry in a minute
 * when the budget resets next month would be a lie that costs it a month of pointless requests.
 */
public final class QuotaExceeded extends GatewayException {

    private final transient QuotaSnapshot snapshot;

    public QuotaExceeded(QuotaSnapshot snapshot) {
        super("Monthly token budget exhausted: used " + snapshot.used() + " of " + snapshot.budget());
        this.snapshot = snapshot;
    }

    public QuotaSnapshot snapshot() {
        return snapshot;
    }
}

package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.in.UsageQueryUseCase;
import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import io.github.mehmetztrk.llmgateway.domain.usage.UsageRecord;
import java.time.Instant;
import java.util.List;

public class UsageQueryService implements UsageQueryUseCase {

    /** A cap so one query cannot pull a month of rows into memory and call it a report. */
    private static final int MAX_LIMIT = 1000;

    private final UsageLedger ledger;

    public UsageQueryService(UsageLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public UsageLedger.UsageSummary summarise(TenantId tenant, Instant from, Instant to) {
        return ledger.summarise(tenant, from, to);
    }

    @Override
    public List<UsageRecord> recent(TenantId tenant, Instant from, Instant to, int limit) {
        return ledger.findByTenant(tenant, from, to, Math.clamp(limit, 1, MAX_LIMIT));
    }
}

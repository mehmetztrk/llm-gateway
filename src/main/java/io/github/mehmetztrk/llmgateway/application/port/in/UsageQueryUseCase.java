package io.github.mehmetztrk.llmgateway.application.port.in;

import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import io.github.mehmetztrk.llmgateway.domain.usage.UsageRecord;
import java.time.Instant;
import java.util.List;

/**
 * Reading the ledger back.
 *
 * <p>Every method takes a {@link TenantId} and there is no "all tenants" variant, so a tenant can
 * only ever read its own usage. That is the same isolation argument as the cache: making the scope
 * a required parameter is stronger than remembering to filter.
 */
public interface UsageQueryUseCase {

    UsageLedger.UsageSummary summarise(TenantId tenant, Instant from, Instant to);

    List<UsageRecord> recent(TenantId tenant, Instant from, Instant to, int limit);
}

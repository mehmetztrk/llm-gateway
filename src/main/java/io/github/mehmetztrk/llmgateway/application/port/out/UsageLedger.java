package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import io.github.mehmetztrk.llmgateway.domain.usage.UsageRecord;
import java.time.Instant;
import java.util.List;

/**
 * Append-only record of what every request cost.
 *
 * <p>{@link #record(UsageRecord)} returns {@code void} and must never block, throw, or slow the
 * request that produced the row. A gateway that fails a successful completion because it could not
 * write an audit row has its priorities backwards — and one that adds a database write to the hot
 * path has thrown away the latency budget the rest of the design protects.
 *
 * <p>There is deliberately no update or delete method. The absence is the design.
 */
public interface UsageLedger {

    /** Fire-and-forget. Buffered and written in batches; see {@code BufferedUsageLedger}. */
    void record(UsageRecord usage);

    List<UsageRecord> findByTenant(TenantId tenant, Instant from, Instant to, int limit);

    UsageSummary summarise(TenantId tenant, Instant from, Instant to);

    /**
     * @param cachedRequests requests served from cache — the number that makes the saving
     *     measurable rather than asserted
     */
    record UsageSummary(
            long requests,
            long cachedRequests,
            long promptTokens,
            long completionTokens,
            long costMicros,
            String currency) {

        public double cacheHitRatio() {
            return requests == 0 ? 0.0 : (double) cachedRequests / requests;
        }
    }
}

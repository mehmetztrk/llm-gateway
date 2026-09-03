package io.github.mehmetztrk.llmgateway.usage;

import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.application.service.UsageRecorder;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import io.github.mehmetztrk.llmgateway.domain.usage.PriceTable;
import io.github.mehmetztrk.llmgateway.domain.usage.UsageRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A recorder that keeps rows in a list instead of a database.
 *
 * <p>Real behaviour rather than a mock, so a test can assert what was recorded. The ledger is the
 * thing an invoice would be reconciled against, and "it was called" is a much weaker claim than
 * "it recorded this".
 */
public final class NoOpUsageRecorder {

    private NoOpUsageRecorder() {}

    /** A recorder whose rows nobody inspects, for tests that are about something else. */
    public static UsageRecorder create() {
        return recordingInto(new RecordingLedger());
    }

    public static UsageRecorder recordingInto(RecordingLedger ledger) {
        // An empty price table: these tests assert on tokens, and a price would only add a number
        // to disagree about.
        return new UsageRecorder(ledger, new PriceTable(Map.of(), "USD"), Clock.systemUTC());
    }

    /** Collects rows so a test can inspect them. */
    public static final class RecordingLedger implements UsageLedger {
        private final List<UsageRecord> rows = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void record(UsageRecord usage) {
            rows.add(usage);
        }

        @Override
        public List<UsageRecord> findByTenant(TenantId tenant, Instant from, Instant to, int limit) {
            return rows.stream()
                    .filter(row -> row.tenantId().equals(tenant))
                    .limit(limit)
                    .toList();
        }

        @Override
        public UsageSummary summarise(TenantId tenant, Instant from, Instant to) {
            List<UsageRecord> mine =
                    rows.stream().filter(row -> row.tenantId().equals(tenant)).toList();
            return new UsageSummary(
                    mine.size(),
                    mine.stream().filter(row -> row.cacheStatus().isHit()).count(),
                    mine.stream().mapToLong(row -> row.usage().promptTokens()).sum(),
                    mine.stream()
                            .mapToLong(row -> row.usage().completionTokens())
                            .sum(),
                    mine.stream().mapToLong(row -> row.cost().micros()).sum(),
                    "USD");
        }

        public List<UsageRecord> rows() {
            return List.copyOf(rows);
        }
    }
}

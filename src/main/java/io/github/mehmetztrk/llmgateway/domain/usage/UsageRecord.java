package io.github.mehmetztrk.llmgateway.domain.usage;

import io.github.mehmetztrk.llmgateway.domain.cache.CacheStatus;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One line in the ledger: what a single request cost, and why.
 *
 * <p>Immutable by construction, and never updated after it is written. Everything needed to explain
 * a charge months later is on the row, including the things that would otherwise require a join
 * into tables that may have changed since.
 *
 * @param outcome whether the request succeeded, so that failed requests are counted rather than
 *     silently absent — a ledger with holes in it cannot be reconciled against anything
 */
public record UsageRecord(
        UUID id,
        TenantId tenantId,
        UUID apiKeyId,
        String model,
        ProviderId provider,
        TokenUsage usage,
        Money cost,
        Duration latency,
        CacheStatus cacheStatus,
        boolean streamed,
        Outcome outcome,
        Instant createdAt) {

    public enum Outcome {
        SUCCESS,
        PROVIDER_ERROR,
        REJECTED
    }

    public UsageRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(cacheStatus, "cacheStatus");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}

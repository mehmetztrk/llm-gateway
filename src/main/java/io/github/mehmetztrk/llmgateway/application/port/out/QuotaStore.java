package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaSnapshot;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Running total of tokens a tenant has spent this month.
 *
 * <p>This counter is a fast approximation used to make an admission decision. The authoritative
 * record is the append-only usage ledger in M7; if the two ever disagree, the ledger is right. That
 * is a deliberate split: an admission check has to answer in microseconds, and a ledger has to be
 * correct.
 */
public interface QuotaStore {

    /** Where the tenant stands right now, without changing anything. */
    Mono<QuotaSnapshot> current(TenantId tenantId, QuotaPolicy policy);

    /**
     * Add actual usage after a completion.
     *
     * <p>Counted after the fact rather than reserved up front, because the completion token count
     * is unknowable before the model has finished. A tenant can therefore overshoot its budget by
     * at most one request — accepted deliberately, and cheaper than the alternative of refusing
     * work that might have fitted.
     */
    Mono<QuotaSnapshot> record(TenantId tenantId, QuotaPolicy policy, long tokens);
}

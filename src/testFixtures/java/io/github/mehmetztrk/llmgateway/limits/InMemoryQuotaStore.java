package io.github.mehmetztrk.llmgateway.limits;

import io.github.mehmetztrk.llmgateway.application.port.out.QuotaStore;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaSnapshot;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/** Monthly counter held in a map, for tests that are not about Redis. */
public class InMemoryQuotaStore implements QuotaStore {

    private final Map<TenantId, AtomicLong> used = new ConcurrentHashMap<>();

    @Override
    public Mono<QuotaSnapshot> current(TenantId tenantId, QuotaPolicy policy) {
        return Mono.just(QuotaSnapshot.evaluate(policy, usedBy(tenantId)));
    }

    @Override
    public Mono<QuotaSnapshot> record(TenantId tenantId, QuotaPolicy policy, long tokens) {
        long total = used.computeIfAbsent(tenantId, key -> new AtomicLong()).addAndGet(tokens);
        return Mono.just(QuotaSnapshot.evaluate(policy, total));
    }

    public long usedBy(TenantId tenantId) {
        return used.getOrDefault(tenantId, new AtomicLong()).get();
    }

    /** Pre-loads consumption so a test can start at any point against a budget. */
    public void preload(TenantId tenantId, long tokens) {
        used.computeIfAbsent(tenantId, key -> new AtomicLong()).set(tokens);
    }
}

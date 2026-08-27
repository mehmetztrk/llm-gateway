package io.github.mehmetztrk.llmgateway.adapter.out.persistence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKey;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the database off the request hot path.
 *
 * <p>Authentication happens on every single request. Without this decorator, every request would
 * pay a connection checkout plus a round trip before any work started — and, more importantly, that
 * cost would sit inside the p99 budget the whole design is measured against.
 *
 * <p><b>Negative results are cached too</b>, and that is not an oversight. Anyone can send requests
 * with random keys; if misses went straight to Postgres, an unauthenticated caller would control
 * how much database load the gateway generates. The bounded size stops that same traffic from
 * evicting the real entries.
 *
 * <p><b>The cost is staleness.</b> A revoked key keeps working until its entry expires. That is a
 * deliberate, bounded trade — the TTL is the revocation SLA, and it is configuration, not an
 * accident. Revocations performed through this instance evict immediately; the TTL only matters for
 * a change made by another replica or directly in the database.
 */
public class CachingTenantRepository implements TenantRepository {

    private final TenantRepository delegate;
    private final Cache<String, Optional<AuthenticatedCaller>> callersByKeyHash;

    public CachingTenantRepository(TenantRepository delegate, Duration ttl, long maximumSize) {
        this.delegate = delegate;
        this.callersByKeyHash = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build();
    }

    @Override
    public Optional<AuthenticatedCaller> findCallerByKeyHash(String keyHash) {
        return callersByKeyHash.get(keyHash, delegate::findCallerByKeyHash);
    }

    @Override
    public boolean revokeApiKey(UUID apiKeyId) {
        boolean revoked = delegate.revokeApiKey(apiKeyId);
        // The cache is keyed by digest and we hold only the key id here, so there is nothing
        // precise to evict. Dropping everything is correct and cheap: revocation is rare, and a
        // brief cold cache is a far smaller problem than a revoked key that still works.
        callersByKeyHash.invalidateAll();
        return revoked;
    }

    @Override
    public void replaceAllowedModels(TenantId tenantId, ModelAllowList allowedModels) {
        delegate.replaceAllowedModels(tenantId, allowedModels);
        // Cached callers carry an embedded allow-list, so a policy change must not wait for the
        // TTL — tightening a policy has to take effect at once to be worth anything.
        callersByKeyHash.invalidateAll();
    }

    @Override
    public void updateLimits(TenantId tenantId, RateLimitPolicy rateLimits, QuotaPolicy quota) {
        delegate.updateLimits(tenantId, rateLimits, quota);
        // Cached callers carry an embedded copy of their tenant's limits, so a tightened limit that
        // waited for the TTL would be useless during the incident it was tightened for.
        callersByKeyHash.invalidateAll();
    }

    @Override
    public Optional<Tenant> findTenantById(TenantId id) {
        return delegate.findTenantById(id);
    }

    @Override
    public Optional<Tenant> findTenantByName(String name) {
        return delegate.findTenantByName(name);
    }

    @Override
    public List<Tenant> findAllTenants() {
        return delegate.findAllTenants();
    }

    @Override
    public Tenant createTenant(String name, ModelAllowList allowedModels) {
        return delegate.createTenant(name, allowedModels);
    }

    @Override
    public ApiKey createApiKey(TenantId tenantId, String keyHash, String keyPrefix, ApiKeyRole role, String label) {
        ApiKey created = delegate.createApiKey(tenantId, keyHash, keyPrefix, role, label);
        // Essential, not defensive. Anything that checks whether a key exists before creating it —
        // the start-up bootstrap does exactly that — leaves a cached *negative* result under this
        // digest. Without this eviction a brand-new credential is rejected until the entry expires,
        // which presents as a key that mysteriously does not work for the first minute.
        callersByKeyHash.invalidate(keyHash);
        return created;
    }

    @Override
    public List<ApiKey> findApiKeysByTenant(TenantId tenantId) {
        return delegate.findApiKeysByTenant(tenantId);
    }

    /** Test seam: lets a test prove the delegate was not consulted a second time. */
    public long estimatedCacheSize() {
        callersByKeyHash.cleanUp();
        return callersByKeyHash.estimatedSize();
    }
}

package io.github.mehmetztrk.llmgateway.domain.tenant;

import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity behind a request, resolved once at the edge and carried through the pipeline.
 *
 * <p>why pass this explicitly into the use cases instead of reading a thread-local or a reactive
 * context deeper down: every quota, cache key and ledger row is scoped by tenant, so tenancy is an
 * input to the business logic, not ambient state. Making it a parameter means a new code path
 * physically cannot forget it — it will not compile.
 */
public record AuthenticatedCaller(Tenant tenant, UUID apiKeyId, ApiKeyRole role, RateLimitPolicy keyRateLimits) {

    public AuthenticatedCaller {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(apiKeyId, "apiKeyId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(keyRateLimits, "keyRateLimits");
    }

    /**
     * Convenience for callers that do not set a per-key limit: the key inherits its tenant's.
     *
     * <p>Inheriting rather than defaulting to unlimited is the safe reading of "not configured" —
     * a key with no explicit limit must not be the way around the tenant's.
     */
    public static AuthenticatedCaller of(Tenant tenant, UUID apiKeyId, ApiKeyRole role) {
        return new AuthenticatedCaller(tenant, apiKeyId, role, tenant.rateLimits());
    }

    public TenantId tenantId() {
        return tenant.id();
    }
}

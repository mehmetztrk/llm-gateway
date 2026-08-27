package io.github.mehmetztrk.llmgateway.domain.tenant;

import io.github.mehmetztrk.llmgateway.domain.error.ModelNotAllowed;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import java.time.Instant;
import java.util.Objects;

/** An organisation using the gateway. */
public record Tenant(
        TenantId id,
        String name,
        boolean active,
        ModelAllowList allowedModels,
        RateLimitPolicy rateLimits,
        QuotaPolicy quota,
        Instant createdAt) {

    public Tenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(allowedModels, "allowedModels");
        Objects.requireNonNull(rateLimits, "rateLimits");
        Objects.requireNonNull(quota, "quota");
        Objects.requireNonNull(createdAt, "createdAt");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tenant name must not be blank");
        }
    }

    /**
     * why this lives on the domain object rather than in the service: it is the tenant's own rule,
     * it needs no collaborators, and putting it here means it cannot be forgotten by a caller that
     * happens to reach a provider by another route.
     */
    public void requireModelAllowed(String model) {
        if (!allowedModels.permits(model)) {
            throw new ModelNotAllowed(model);
        }
    }
}

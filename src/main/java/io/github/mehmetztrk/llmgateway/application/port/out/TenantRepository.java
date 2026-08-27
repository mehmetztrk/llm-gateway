package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKey;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for tenants and keys.
 *
 * <p>why this port is blocking while the rest of the application is reactive: the adapter is JDBC,
 * and pretending otherwise by returning {@code Mono} here would hide where the boundary is. The
 * scheduler hop is applied deliberately and visibly at the one call site that needs it — see
 * ADR-0006 and {@code ApiKeyAuthenticationManager}.
 */
public interface TenantRepository {

    /**
     * Resolve a caller from the digest of a presented key.
     *
     * <p>Returns empty for an unknown digest, a revoked key, or an inactive tenant. The caller is
     * not told which, because the distinction is only useful to someone probing.
     */
    Optional<AuthenticatedCaller> findCallerByKeyHash(String keyHash);

    Optional<Tenant> findTenantById(TenantId id);

    Optional<Tenant> findTenantByName(String name);

    List<Tenant> findAllTenants();

    Tenant createTenant(String name, ModelAllowList allowedModels);

    void replaceAllowedModels(TenantId tenantId, ModelAllowList allowedModels);

    /**
     * Store a new key. The plaintext never reaches this method — only its digest, so no
     * implementation is even able to persist something reversible by accident.
     */
    ApiKey createApiKey(TenantId tenantId, String keyHash, String keyPrefix, ApiKeyRole role, String label);

    List<ApiKey> findApiKeysByTenant(TenantId tenantId);

    /** @return true if a key was revoked by this call, false if it was already revoked or absent */
    boolean revokeApiKey(UUID apiKeyId);
}

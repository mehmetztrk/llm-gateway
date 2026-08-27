package io.github.mehmetztrk.llmgateway.application.port.in;

import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKey;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.util.List;
import java.util.UUID;

/**
 * Tenant and key administration.
 *
 * <p>Synchronous on purpose: these operations are rare, human-driven and backed by a blocking
 * repository. Wrapping them in {@code Mono} here would only move the scheduler hop somewhere less
 * visible — the web adapter applies it explicitly instead. See ADR-0006.
 */
public interface AdminUseCase {

    Tenant createTenant(String name, ModelAllowList allowedModels);

    List<Tenant> listTenants();

    Tenant getTenant(TenantId id);

    void setAllowedModels(TenantId tenantId, ModelAllowList allowedModels);

    /**
     * Issue a key.
     *
     * @return the stored record together with the plaintext, which exists in memory only for the
     *     duration of this response and is never recoverable afterwards
     */
    IssuedApiKey issueKey(TenantId tenantId, ApiKeyRole role, String label);

    List<ApiKey> listKeys(TenantId tenantId);

    boolean revokeKey(UUID keyId);

    /** The one and only time a caller ever sees {@code plaintext}. */
    record IssuedApiKey(ApiKey record, String plaintext) {}
}

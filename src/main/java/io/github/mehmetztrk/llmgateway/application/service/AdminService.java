package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.in.AdminUseCase;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyFactory;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.domain.error.TenantNotFound;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKey;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.util.List;
import java.util.UUID;

public class AdminService implements AdminUseCase {

    private final TenantRepository tenants;
    private final ApiKeyFactory keyFactory;
    private final ApiKeyHasher hasher;

    public AdminService(TenantRepository tenants, ApiKeyFactory keyFactory, ApiKeyHasher hasher) {
        this.tenants = tenants;
        this.keyFactory = keyFactory;
        this.hasher = hasher;
    }

    @Override
    public Tenant createTenant(String name, ModelAllowList allowedModels) {
        return tenants.createTenant(name, allowedModels);
    }

    @Override
    public List<Tenant> listTenants() {
        return tenants.findAllTenants();
    }

    @Override
    public Tenant getTenant(TenantId id) {
        return tenants.findTenantById(id).orElseThrow(() -> new TenantNotFound(id.toString()));
    }

    @Override
    public void setAllowedModels(TenantId tenantId, ModelAllowList allowedModels) {
        getTenant(tenantId);
        tenants.replaceAllowedModels(tenantId, allowedModels);
    }

    @Override
    public void setLimits(TenantId tenantId, RateLimitPolicy rateLimits, QuotaPolicy quota) {
        getTenant(tenantId);
        tenants.updateLimits(tenantId, rateLimits, quota);
    }

    @Override
    public IssuedApiKey issueKey(TenantId tenantId, ApiKeyRole role, String label) {
        getTenant(tenantId);

        String plaintext = keyFactory.generate();
        // The digest is computed here and the plaintext is never handed to the repository, so no
        // persistence implementation is even in a position to store something reversible.
        ApiKey stored = tenants.createApiKey(
                tenantId, hasher.hash(plaintext), keyFactory.displayPrefix(plaintext), role, label);
        return new IssuedApiKey(stored, plaintext);
    }

    @Override
    public List<ApiKey> listKeys(TenantId tenantId) {
        getTenant(tenantId);
        return tenants.findApiKeysByTenant(tenantId);
    }

    @Override
    public boolean revokeKey(UUID keyId) {
        return tenants.revokeApiKey(keyId);
    }
}

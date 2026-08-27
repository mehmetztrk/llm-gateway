package io.github.mehmetztrk.llmgateway.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKey;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Repository behaviour against the real schema.
 *
 * <p>These assertions are about SQL and constraints, which is exactly the layer an in-memory
 * database would let pass while Postgres rejected it.
 */
class TenantRepositoryIT extends AbstractGatewayIT {

    @Autowired
    private TenantRepository repository;

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("a caller is resolved from a key digest in a single lookup")
    void resolvesCallerByKeyHash() {
        Tenant tenant = repository.createTenant(uniqueName("repo"), ModelAllowList.of("mock-fast", "mock-echo"));
        String hash = "hash-" + UUID.randomUUID();
        repository.createApiKey(tenant.id(), hash, "llmgw_abc...", ApiKeyRole.TENANT, null);

        ApiKey key = repository.findApiKeysByTenant(tenant.id()).getFirst();
        Optional<AuthenticatedCaller> caller = repository.findCallerByKeyHash(hash);

        assertThat(caller).isPresent();
        assertThat(caller.get().tenantId()).isEqualTo(tenant.id());
        assertThat(caller.get().apiKeyId()).isEqualTo(key.id());
        assertThat(caller.get().role()).isEqualTo(ApiKeyRole.TENANT);
        // The allow-list is joined in the same query rather than fetched separately.
        assertThat(caller.get().tenant().allowedModels().models()).containsExactlyInAnyOrder("mock-fast", "mock-echo");
    }

    @Test
    @DisplayName("an unknown digest resolves to empty rather than throwing")
    void unknownHashResolvesToEmpty() {
        assertThat(repository.findCallerByKeyHash("no-such-hash")).isEmpty();
    }

    @Test
    @DisplayName("a revoked key no longer resolves")
    void revokedKeyDoesNotResolve() {
        Tenant tenant = repository.createTenant(uniqueName("revoked"), ModelAllowList.ANY);
        String hash = "hash-" + UUID.randomUUID();
        repository.createApiKey(tenant.id(), hash, "llmgw_rev...", ApiKeyRole.TENANT, null);
        ApiKey key = repository.findApiKeysByTenant(tenant.id()).getFirst();

        assertThat(repository.findCallerByKeyHash(hash)).isPresent();
        assertThat(repository.revokeApiKey(key.id())).isTrue();
        assertThat(repository.findCallerByKeyHash(hash)).isEmpty();
        // Second revoke changes nothing, so the caller can tell a real revocation from a repeat.
        assertThat(repository.revokeApiKey(key.id())).isFalse();
    }

    @Test
    @DisplayName("revocation is a soft delete: the row survives for the audit trail")
    void revocationKeepsTheRow() {
        Tenant tenant = repository.createTenant(uniqueName("audit"), ModelAllowList.ANY);
        repository.createApiKey(tenant.id(), "hash-" + UUID.randomUUID(), "llmgw_aud...", ApiKeyRole.TENANT, "ci");
        ApiKey key = repository.findApiKeysByTenant(tenant.id()).getFirst();

        repository.revokeApiKey(key.id());

        ApiKey after = repository.findApiKeysByTenant(tenant.id()).getFirst();
        assertThat(after.isRevoked()).isTrue();
        assertThat(after.revokedAt()).isNotNull();
        assertThat(after.label()).isEqualTo("ci");
    }

    @Test
    @DisplayName("duplicate tenant names are rejected by the database, not by hope")
    void tenantNamesAreUnique() {
        String name = uniqueName("dup");
        repository.createTenant(name, ModelAllowList.ANY);
        assertThatThrownBy(() -> repository.createTenant(name, ModelAllowList.ANY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("duplicate key digests are rejected, so a lookup can never be ambiguous")
    void keyHashesAreUnique() {
        Tenant a = repository.createTenant(uniqueName("hash-a"), ModelAllowList.ANY);
        Tenant b = repository.createTenant(uniqueName("hash-b"), ModelAllowList.ANY);
        String hash = "shared-" + UUID.randomUUID();

        repository.createApiKey(a.id(), hash, "llmgw_a...", ApiKeyRole.TENANT, null);

        // Without the unique constraint this would silently authenticate as one of two tenants.
        assertThatThrownBy(() -> repository.createApiKey(b.id(), hash, "llmgw_b...", ApiKeyRole.TENANT, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("replacing the allow-list is a full replacement, not a merge")
    void allowListIsReplacedWholesale() {
        Tenant tenant = repository.createTenant(uniqueName("models"), ModelAllowList.of("a", "b", "c"));

        repository.replaceAllowedModels(tenant.id(), ModelAllowList.of("a"));

        assertThat(repository.findTenantById(tenant.id()))
                .get()
                .satisfies(
                        updated -> assertThat(updated.allowedModels().models()).containsExactly("a"));
    }

    @Test
    @DisplayName("keys of one tenant are never returned for another")
    void keysAreScopedToTheirTenant() {
        Tenant a = repository.createTenant(uniqueName("iso-a"), ModelAllowList.ANY);
        Tenant b = repository.createTenant(uniqueName("iso-b"), ModelAllowList.ANY);
        repository.createApiKey(a.id(), "hash-" + UUID.randomUUID(), "llmgw_a...", ApiKeyRole.TENANT, null);

        assertThat(repository.findApiKeysByTenant(b.id())).isEmpty();
        assertThat(repository.findApiKeysByTenant(a.id())).hasSize(1);
    }
}

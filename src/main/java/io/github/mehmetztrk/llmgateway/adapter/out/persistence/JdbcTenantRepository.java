package io.github.mehmetztrk.llmgateway.adapter.out.persistence;

import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKey;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Blocking JDBC, deliberately.
 *
 * <p>why {@link JdbcClient} rather than {@code JdbcTemplate} or Spring Data JDBC: the queries here
 * are few, hand-written and performance-relevant, so a repository abstraction that generates SQL
 * would hide the one thing worth reading. {@code JdbcClient} is the modern fluent API over the same
 * machinery — named parameters, no anonymous {@code Object[]} argument arrays to get out of order.
 *
 * <p>Nothing in this class may be called from an event-loop thread. The scheduler hop that makes
 * that true lives in {@code CachingTenantRepository}. See ADR-0006.
 */
public class JdbcTenantRepository implements TenantRepository {

    private final JdbcClient jdbc;

    public JdbcTenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AuthenticatedCaller> findCallerByKeyHash(String keyHash) {
        // One round trip for the whole authentication decision: key, tenant and allow-list.
        // Splitting it into three queries would triple the latency of every cache miss.
        record Row(UUID keyId, String role, UUID tenantId, String tenantName, Instant createdAt, String model) {}

        List<Row> rows = jdbc.sql("""
                        select k.id            as key_id,
                               k.role          as role,
                               t.id            as tenant_id,
                               t.name          as tenant_name,
                               t.created_at    as created_at,
                               m.model         as model
                          from api_keys k
                          join tenants t on t.id = k.tenant_id
                          left join tenant_models m on m.tenant_id = t.id
                         where k.key_hash = :hash
                           and k.revoked_at is null
                           and t.active = true
                        """)
                .param("hash", keyHash)
                .query((ResultSet rs, int rowNum) -> new Row(
                        rs.getObject("key_id", UUID.class),
                        rs.getString("role"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("model")))
                .list();

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Row first = rows.getFirst();
        Set<String> models = new HashSet<>();
        for (Row row : rows) {
            if (row.model() != null) {
                models.add(row.model());
            }
        }

        Tenant tenant = new Tenant(
                TenantId.of(first.tenantId()), first.tenantName(), true, new ModelAllowList(models), first.createdAt());
        return Optional.of(new AuthenticatedCaller(tenant, first.keyId(), ApiKeyRole.valueOf(first.role())));
    }

    @Override
    public Optional<Tenant> findTenantById(TenantId id) {
        return jdbc.sql("select id, name, active, created_at from tenants where id = :id")
                .param("id", id.value())
                .query(this::mapTenant)
                .optional()
                .map(this::withAllowedModels);
    }

    @Override
    public Optional<Tenant> findTenantByName(String name) {
        return jdbc.sql("select id, name, active, created_at from tenants where name = :name")
                .param("name", name)
                .query(this::mapTenant)
                .optional()
                .map(this::withAllowedModels);
    }

    @Override
    public List<Tenant> findAllTenants() {
        return jdbc
                .sql("select id, name, active, created_at from tenants order by created_at")
                .query(this::mapTenant)
                .list()
                .stream()
                .map(this::withAllowedModels)
                .toList();
    }

    @Override
    @Transactional
    public Tenant createTenant(String name, ModelAllowList allowedModels) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into tenants (id, name) values (:id, :name)")
                .param("id", id)
                .param("name", name)
                .update();
        insertModels(TenantId.of(id), allowedModels);
        return findTenantById(TenantId.of(id)).orElseThrow();
    }

    @Override
    @Transactional
    public void replaceAllowedModels(TenantId tenantId, ModelAllowList allowedModels) {
        jdbc.sql("delete from tenant_models where tenant_id = :id")
                .param("id", tenantId.value())
                .update();
        insertModels(tenantId, allowedModels);
    }

    @Override
    public ApiKey createApiKey(TenantId tenantId, String keyHash, String keyPrefix, ApiKeyRole role, String label) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into api_keys (id, tenant_id, key_hash, key_prefix, role, label)
                        values (:id, :tenantId, :hash, :prefix, :role, :label)
                        """)
                .param("id", id)
                .param("tenantId", tenantId.value())
                .param("hash", keyHash)
                .param("prefix", keyPrefix)
                .param("role", role.name())
                .param("label", label)
                .update();
        return findApiKeysByTenant(tenantId).stream()
                .filter(key -> key.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public List<ApiKey> findApiKeysByTenant(TenantId tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, key_prefix, role, label, created_at, revoked_at
                          from api_keys
                         where tenant_id = :id
                         order by created_at
                        """)
                .param("id", tenantId.value())
                .query((ResultSet rs, int rowNum) -> new ApiKey(
                        rs.getObject("id", UUID.class),
                        TenantId.of(rs.getObject("tenant_id", UUID.class)),
                        rs.getString("key_prefix"),
                        ApiKeyRole.valueOf(rs.getString("role")),
                        rs.getString("label"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null
                                ? null
                                : rs.getTimestamp("revoked_at").toInstant()))
                .list();
    }

    @Override
    public boolean revokeApiKey(UUID apiKeyId) {
        // The `revoked_at is null` guard makes this idempotent and lets the caller tell a real
        // revocation from a repeat, without a separate read.
        return jdbc.sql("update api_keys set revoked_at = now() where id = :id and revoked_at is null")
                        .param("id", apiKeyId)
                        .update()
                > 0;
    }

    private void insertModels(TenantId tenantId, ModelAllowList allowedModels) {
        for (String model : allowedModels.models()) {
            jdbc.sql("insert into tenant_models (tenant_id, model) values (:id, :model)")
                    .param("id", tenantId.value())
                    .param("model", model)
                    .update();
        }
    }

    private Tenant mapTenant(ResultSet rs, int rowNum) throws SQLException {
        return new Tenant(
                TenantId.of(rs.getObject("id", UUID.class)),
                rs.getString("name"),
                rs.getBoolean("active"),
                ModelAllowList.NONE,
                rs.getTimestamp("created_at").toInstant());
    }

    private Tenant withAllowedModels(Tenant tenant) {
        List<String> models = jdbc.sql("select model from tenant_models where tenant_id = :id")
                .param("id", tenant.id().value())
                .query(String.class)
                .list();
        return new Tenant(
                tenant.id(),
                tenant.name(),
                tenant.active(),
                new ModelAllowList(Set.copyOf(models)),
                tenant.createdAt());
    }
}

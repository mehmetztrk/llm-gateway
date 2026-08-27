package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyFactory;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Solves the empty-database problem: a fresh deployment has no key, and creating one requires a
 * key.
 *
 * <p>When {@code gateway.security.bootstrap-admin-key} is set, that key and an {@code admin} tenant
 * are created if they do not already exist. Nothing is created when the property is unset, so a
 * production deployment that forgets to configure one fails closed and loudly rather than shipping
 * a default credential — the single most common way a system like this gets compromised.
 *
 * <p>Idempotent: it checks whether the key already resolves before inserting anything, so restarts
 * and multiple replicas do not accumulate duplicates.
 */
public class SecurityBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityBootstrap.class);

    private static final String ADMIN_TENANT = "admin";
    private static final String DEMO_TENANT = "demo";

    private final TenantRepository tenants;
    private final ApiKeyHasher hasher;
    private final ApiKeyFactory keyFactory;
    private final SecurityProperties properties;

    public SecurityBootstrap(
            TenantRepository tenants, ApiKeyHasher hasher, ApiKeyFactory keyFactory, SecurityProperties properties) {
        this.tenants = tenants;
        this.hasher = hasher;
        this.keyFactory = keyFactory;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isBlank(properties.bootstrapAdminKey()) && isBlank(properties.bootstrapTenantKey())) {
            log.info("no bootstrap keys configured; the gateway will reject every request until a key exists");
            return;
        }

        warnIfDefaultPepper();

        ensureKey(ADMIN_TENANT, ModelAllowList.ANY, ApiKeyRole.ADMIN, properties.bootstrapAdminKey());
        ensureKey(DEMO_TENANT, ModelAllowList.ANY, ApiKeyRole.TENANT, properties.bootstrapTenantKey());
    }

    private void ensureKey(String tenantName, ModelAllowList models, ApiKeyRole role, String key) {
        if (isBlank(key)) {
            return;
        }
        String hash = hasher.hash(key);
        if (tenants.findCallerByKeyHash(hash).isPresent()) {
            log.info("bootstrap {} key already present ({})", role, keyFactory.displayPrefix(key));
            return;
        }

        Tenant tenant = tenants.findTenantByName(tenantName).orElseGet(() -> tenants.createTenant(tenantName, models));

        // Only the digest and the display prefix are stored; the plaintext came from configuration
        // and is never written anywhere by this class — including the log line below.
        tenants.createApiKey(tenant.id(), hash, keyFactory.displayPrefix(key), role, "bootstrap");
        log.info("created bootstrap {} key {} for tenant '{}'", role, keyFactory.displayPrefix(key), tenantName);
    }

    private void warnIfDefaultPepper() {
        if ("change-me-in-production".equals(properties.keyPepper())) {
            log.warn("gateway.security.key-pepper is still the default value — set it before any real deployment");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

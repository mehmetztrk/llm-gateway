package io.github.mehmetztrk.llmgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code gateway.security}.
 *
 * @param keyPepper secret mixed into every key digest. Held in configuration and never in the
 *     database, so a database dump alone cannot be used to verify guessed keys offline. Changing it
 *     invalidates every existing key — which is the intended emergency lever.
 * @param cacheTtl how long a resolved key stays cached. This value <em>is</em> the revocation SLA
 *     for changes made outside this instance: a key revoked elsewhere keeps working for at most
 *     this long. Short enough to be defensible, long enough that the database is off the hot path.
 * @param cacheMaxSize bound on cached entries, including failed lookups. Without a bound, traffic
 *     with random keys would decide how much memory the gateway uses.
 * @param bootstrapAdminKey optional. When set at start-up, an {@code admin} tenant and this key are
 *     created if absent. It is the only way to get the first credential into a fresh database —
 *     without it a new deployment has no way to authenticate the call that would create one.
 * @param bootstrapTenantKey optional demo credential, intended for the local profile only.
 */
@ConfigurationProperties(prefix = "gateway.security")
public record SecurityProperties(
        @DefaultValue("change-me-in-production") String keyPepper,
        @DefaultValue("60s") Duration cacheTtl,
        @DefaultValue("10000") long cacheMaxSize,
        String bootstrapAdminKey,
        String bootstrapTenantKey) {

    public SecurityProperties {
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must not be negative");
        }
        if (cacheMaxSize <= 0) {
            throw new IllegalArgumentException("cacheMaxSize must be positive");
        }
    }
}

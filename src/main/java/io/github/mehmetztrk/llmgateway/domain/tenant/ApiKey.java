package io.github.mehmetztrk.llmgateway.domain.tenant;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored key record. Deliberately does <b>not</b> contain the key.
 *
 * <p>The plaintext exists exactly once, in the response to the call that created it. After that
 * only {@link #keyPrefix()} survives, which is enough to recognise a key in a list and useless for
 * calling the API.
 */
public record ApiKey(
        UUID id,
        TenantId tenantId,
        String keyPrefix,
        ApiKeyRole role,
        String label,
        Instant createdAt,
        Instant revokedAt) {

    public ApiKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}

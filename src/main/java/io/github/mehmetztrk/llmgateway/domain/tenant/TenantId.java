package io.github.mehmetztrk.llmgateway.domain.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a tenant.
 *
 * <p>why a wrapper around UUID: this value decides which cache entries, which quota counters and
 * which ledger rows a request may touch. Every cross-tenant leak starts with the wrong identifier
 * being passed somewhere it type-checks. A distinct type makes that a compile error.
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId of(UUID value) {
        return new TenantId(value);
    }

    public static TenantId fromString(String value) {
        return new TenantId(UUID.fromString(value));
    }

    public static TenantId random() {
        return new TenantId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

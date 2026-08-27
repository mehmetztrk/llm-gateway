package io.github.mehmetztrk.llmgateway.domain.tenant;

/**
 * What a key is allowed to do.
 *
 * <p>Only two levels on purpose. A finer-grained permission model is easy to add and hard to get
 * right, and nothing in this system yet needs a distinction the two values cannot express.
 */
public enum ApiKeyRole {
    /** May call {@code /v1/**} on behalf of its tenant. Nothing else. */
    TENANT,

    /** May additionally manage tenants and keys through {@code /admin/**}. */
    ADMIN;

    public boolean canAdminister() {
        return this == ADMIN;
    }
}

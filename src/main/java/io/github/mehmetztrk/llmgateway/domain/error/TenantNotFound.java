package io.github.mehmetztrk.llmgateway.domain.error;

/** An admin operation referenced a tenant that does not exist. Maps to HTTP 404. */
public final class TenantNotFound extends GatewayException {

    public TenantNotFound(String identifier) {
        super("No tenant with identifier '" + identifier + "'");
    }
}

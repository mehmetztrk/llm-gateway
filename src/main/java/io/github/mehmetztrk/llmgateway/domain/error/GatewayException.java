package io.github.mehmetztrk.llmgateway.domain.error;

/**
 * Base type for every failure the gateway can describe in business terms.
 *
 * <p>why sealed <em>and</em> abstract: sealing closes the set of subtypes, but a non-abstract base
 * class is still instantiable, which forces every switch to carry a {@code default} branch — and a
 * default branch is exactly what swallows a newly added failure mode. Abstract plus sealed makes
 * the switch in the HTTP error mapper exhaustive, so adding a subtype without assigning it a status
 * code fails the build instead of silently becoming a 500 in production.
 *
 * <p>Subtypes are added as the milestones that introduce them land, each carrying the data its HTTP
 * representation needs.
 */
public abstract sealed class GatewayException extends RuntimeException
        permits ModelNotAllowed, ModelNotFound, ProviderCallFailed, FeatureNotSupported {

    protected GatewayException(String message) {
        super(message);
    }

    protected GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}

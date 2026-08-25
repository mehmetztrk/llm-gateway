package io.github.mehmetztrk.llmgateway.domain.error;

/**
 * Base type for every failure the gateway can describe in business terms.
 *
 * <p>why sealed: the set of things that can go wrong in a gateway is closed and known. Sealing it
 * means the HTTP error mapper can switch over the permitted subtypes and the compiler will fail the
 * build when a new failure mode is added without a matching status code — instead of it silently
 * becoming a 500 in production.
 *
 * <p>Subtypes are added as the milestones that introduce them land (rate limiting in M4, routing in
 * M5, and so on), each carrying the data its HTTP representation needs.
 */
public sealed class GatewayException extends RuntimeException permits ModelNotAllowed {

    protected GatewayException(String message) {
        super(message);
    }

    protected GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}

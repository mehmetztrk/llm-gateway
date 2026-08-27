package io.github.mehmetztrk.llmgateway.domain.error;

/**
 * The request asked for something this build does not implement yet. Maps to HTTP 400.
 *
 * <p>why an explicit failure rather than quietly ignoring the parameter: a client that sends
 * {@code stream: true} and receives a single JSON body has been lied to, and will fail in a
 * confusing place. Refusing loudly is kinder than a silent downgrade.
 */
public final class FeatureNotSupported extends GatewayException {

    private final String feature;

    public FeatureNotSupported(String feature, String detail) {
        super("'" + feature + "' is not supported: " + detail);
        this.feature = feature;
    }

    public String feature() {
        return feature;
    }
}

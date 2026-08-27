package io.github.mehmetztrk.llmgateway.domain.routing;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identifier for a configured provider instance, e.g. {@code ollama-primary}.
 *
 * <p>why a wrapper rather than a bare String: this value ends up in metric labels, span attributes
 * and ledger rows, where a typo is silent and permanent. A single-field record costs nothing at
 * runtime and makes the compiler refuse to swap it with a model name or a tenant id.
 */
public record ProviderId(String value) implements Comparable<ProviderId> {

    private static final Pattern VALID = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    public ProviderId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "provider id must be lowercase alphanumeric with dashes, was: '" + value + "'");
        }
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }

    @Override
    public int compareTo(ProviderId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}

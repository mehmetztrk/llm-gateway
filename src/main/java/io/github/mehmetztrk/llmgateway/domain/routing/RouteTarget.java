package io.github.mehmetztrk.llmgateway.domain.routing;

import java.util.Objects;

/**
 * One place a request can actually be sent: a specific provider, and the model name that provider
 * knows it by.
 *
 * <p>The two halves are separate because they genuinely differ. A tenant asking for
 * {@code chat-default} may be served by {@code qwen2.5:1.5b-instruct} on the GPU or by
 * {@code llama3.2:1b} on the CPU fallback — same intent, different upstream names. Collapsing them
 * into one string is what makes failover impossible to express.
 */
public record RouteTarget(ProviderId provider, String model) {

    public RouteTarget {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("target model must not be blank");
        }
    }

    public static RouteTarget of(String provider, String model) {
        return new RouteTarget(ProviderId.of(provider), model);
    }

    @Override
    public String toString() {
        return provider + "/" + model;
    }
}

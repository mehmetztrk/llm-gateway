package io.github.mehmetztrk.llmgateway.domain.error;

import io.github.mehmetztrk.llmgateway.domain.routing.RouteTarget;
import java.util.List;

/**
 * Every candidate for a model was tried and every one failed. Maps to HTTP 503.
 *
 * <p>Distinct from {@link ProviderCallFailed}, which is one provider failing. This is the whole
 * route being exhausted, which is a different operational situation and deserves a different status:
 * 502 says "the thing behind me is broken", 503 says "I have nothing left to try".
 */
public final class NoProviderAvailable extends GatewayException {

    private final transient List<RouteTarget> attempted;

    public NoProviderAvailable(String model, List<RouteTarget> attempted, Throwable lastFailure) {
        super("No provider could serve '" + model + "' after trying " + attempted, lastFailure);
        this.attempted = List.copyOf(attempted);
    }

    public List<RouteTarget> attempted() {
        return attempted;
    }
}

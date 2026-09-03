package io.github.mehmetztrk.llmgateway.application.port.out;

import io.github.mehmetztrk.llmgateway.domain.routing.ProviderHealth;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.util.Map;

/**
 * The gateway's current belief about each provider.
 *
 * <p>Read on the request path, so it must answer from memory. Deliberately not reactive: a routing
 * decision that had to await a health lookup would be a round trip added to every request in order
 * to avoid a round trip.
 */
public interface ProviderHealthRegistry {

    ProviderHealth healthOf(ProviderId provider);

    Map<ProviderId, ProviderHealth> snapshot();

    /**
     * Record the outcome of a real request.
     *
     * <p>why feed live traffic in rather than relying on the periodic probe alone: a probe every few
     * seconds can be up to that interval out of date, and during a failover that interval is the
     * whole story. A request that just failed is the freshest evidence available.
     */
    void recordSuccess(ProviderId provider);

    void recordFailure(ProviderId provider);
}

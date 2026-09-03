package io.github.mehmetztrk.llmgateway.domain.routing;

/**
 * What the gateway believes about a provider right now.
 *
 * <p>Three states rather than two, because "we have not asked yet" is genuinely different from
 * "we asked and it failed". Treating an unprobed provider as down would make every cold start an
 * outage; treating it as up would send the first request into the dark. {@link #UNKNOWN} is
 * routable but ranked below a provider we have actually seen answer.
 */
public enum ProviderHealth {
    UP,
    UNKNOWN,
    DOWN;

    /** Lower sorts first. */
    public int preference() {
        return switch (this) {
            case UP -> 0;
            case UNKNOWN -> 1;
            case DOWN -> 2;
        };
    }

    public boolean isRoutable() {
        return this != DOWN;
    }
}

package io.github.mehmetztrk.llmgateway.application.service;

import io.github.mehmetztrk.llmgateway.application.port.out.QuotaStore;
import io.github.mehmetztrk.llmgateway.application.port.out.RateLimiter;
import io.github.mehmetztrk.llmgateway.domain.error.QuotaExceeded;
import io.github.mehmetztrk.llmgateway.domain.error.RateLimitExceeded;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaSnapshot;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import reactor.core.publisher.Mono;

/**
 * Admission control: may this request proceed, and what should the response headers say?
 *
 * <p><b>Four buckets, checked in a fixed order.</b> Requests before tokens, and tenant before key.
 * The order is not arbitrary — buckets are consumed as they are checked, so a request refused by a
 * later bucket has already spent capacity in the earlier ones. Checking the cheapest and most
 * commonly hit limit first keeps that waste small and makes the reported reason the one an operator
 * would consider the real cause.
 *
 * <p><b>Tokens are charged in two phases.</b> The completion token count does not exist until the
 * model has finished, so admission uses an estimate of the prompt and {@link #settle} charges the
 * difference afterwards. A caller can therefore exceed its token rate by at most the size of one
 * response, which then delays its next request rather than retroactively failing this one.
 */
public class RateLimitService {

    private final RateLimiter rateLimiter;
    private final QuotaStore quotas;

    public RateLimitService(RateLimiter rateLimiter, QuotaStore quotas) {
        this.rateLimiter = rateLimiter;
        this.quotas = quotas;
    }

    /**
     * @param estimatedTokens up-front estimate, normally the prompt length
     * @return the snapshots to report in the response headers
     */
    public Mono<Admission> admit(AuthenticatedCaller caller, long estimatedTokens) {
        RateLimitPolicy tenantPolicy = caller.tenant().rateLimits();
        // The key may be configured tighter than its tenant, never looser: a per-key limit is a
        // sub-allocation of the tenant's budget, not an exemption from it.
        RateLimitPolicy keyPolicy = caller.keyRateLimits().strictest(tenantPolicy);

        String tenantScope = "tenant:" + caller.tenantId().value();
        String keyScope = "key:" + caller.apiKeyId();

        // The tenant snapshots are the ones reported in headers: a client sharing an organisation's
        // budget needs to see the shared ceiling, which is the one it will actually hit.
        return consume(
                        tenantScope + ":req",
                        tenantPolicy.requestsPerMinute(),
                        1,
                        RateLimitExceeded.Scope.TENANT_REQUESTS)
                .flatMap(requests -> consume(
                                keyScope + ":req",
                                keyPolicy.requestsPerMinute(),
                                1,
                                RateLimitExceeded.Scope.KEY_REQUESTS)
                        .then(consume(
                                tenantScope + ":tok",
                                tenantPolicy.tokensPerMinute(),
                                estimatedTokens,
                                RateLimitExceeded.Scope.TENANT_TOKENS))
                        .flatMap(tokens -> consume(
                                        keyScope + ":tok",
                                        keyPolicy.tokensPerMinute(),
                                        estimatedTokens,
                                        RateLimitExceeded.Scope.KEY_TOKENS)
                                .then(quotas.current(
                                        caller.tenantId(), caller.tenant().quota()))
                                .flatMap(quota -> quota.isExceeded()
                                        ? Mono.<Admission>error(new QuotaExceeded(quota))
                                        : Mono.just(new Admission(requests, tokens, quota)))));
    }

    /**
     * Charge what the request actually cost, once it is known.
     *
     * <p>Never refuses and never fails the request: the work is done and the tokens are spent, so
     * the only honest thing left is to record it. A failure here is swallowed deliberately —
     * returning an error to a client whose completion succeeded would be a lie.
     */
    public Mono<QuotaSnapshot> settle(AuthenticatedCaller caller, long estimatedTokens, long actualTokens) {
        long difference = Math.max(0, actualTokens - estimatedTokens);
        RateLimitPolicy tenantPolicy = caller.tenant().rateLimits();
        RateLimitPolicy keyPolicy = caller.keyRateLimits().strictest(tenantPolicy);

        return rateLimiter
                .settle("tenant:" + caller.tenantId().value() + ":tok", tenantPolicy.tokensPerMinute(), difference)
                .then(rateLimiter.settle("key:" + caller.apiKeyId() + ":tok", keyPolicy.tokensPerMinute(), difference))
                .then(quotas.record(caller.tenantId(), caller.tenant().quota(), actualTokens))
                .onErrorResume(error -> Mono.just(QuotaSnapshot.UNLIMITED));
    }

    private Mono<RateLimitSnapshot> consume(String bucketKey, long limit, long permits, RateLimitExceeded.Scope scope) {
        return rateLimiter
                .tryConsume(bucketKey, limit, permits)
                .flatMap(snapshot ->
                        snapshot.allowed() ? Mono.just(snapshot) : Mono.error(new RateLimitExceeded(scope, snapshot)));
    }

    /** What the response headers need to say about this request. */
    public record Admission(RateLimitSnapshot requests, RateLimitSnapshot tokens, QuotaSnapshot quota) {}
}

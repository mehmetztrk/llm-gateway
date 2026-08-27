package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.application.port.in.GatewayResult;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaSnapshot;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;
import org.springframework.http.HttpHeaders;

/**
 * The rate-limit headers OpenAI clients already know how to read.
 *
 * <p>Names match OpenAI's exactly ({@code x-ratelimit-limit-requests} and friends) rather than the
 * IETF draft's {@code RateLimit-*}. A gateway whose promise is "change base_url and nothing else"
 * does not get to pick the tidier standard: existing SDK middleware and retry helpers look for
 * these strings.
 *
 * <p>Headers are written on <b>successful</b> responses too, not only on 429s. A client that only
 * learns its budget by being refused has no way to slow down before it is.
 */
final class RateLimitHeaders {

    static final String LIMIT_REQUESTS = "x-ratelimit-limit-requests";
    static final String REMAINING_REQUESTS = "x-ratelimit-remaining-requests";
    static final String RESET_REQUESTS = "x-ratelimit-reset-requests";
    static final String LIMIT_TOKENS = "x-ratelimit-limit-tokens";
    static final String REMAINING_TOKENS = "x-ratelimit-remaining-tokens";
    static final String RESET_TOKENS = "x-ratelimit-reset-tokens";

    /** Not an OpenAI header — this gateway's own signal that a budget is running out. */
    static final String QUOTA_WARNING = "x-llmgw-quota-warning";

    static final String QUOTA_LIMIT = "x-llmgw-quota-limit-tokens";
    static final String QUOTA_REMAINING = "x-llmgw-quota-remaining-tokens";

    private RateLimitHeaders() {}

    static void apply(HttpHeaders headers, GatewayResult<?> result) {
        applyBucket(headers, result.requests(), LIMIT_REQUESTS, REMAINING_REQUESTS, RESET_REQUESTS);
        applyBucket(headers, result.tokens(), LIMIT_TOKENS, REMAINING_TOKENS, RESET_TOKENS);
        applyQuota(headers, result.quota());
    }

    private static void applyBucket(
            HttpHeaders headers, RateLimitSnapshot snapshot, String limit, String remaining, String reset) {
        headers.set(limit, Long.toString(snapshot.limit()));
        headers.set(remaining, Long.toString(snapshot.remaining()));
        // Seconds until the bucket would be full again, which is what a client needs to decide how
        // long to back off for. Zero means there is capacity right now.
        headers.set(reset, Long.toString(snapshot.retryAfterSeconds()));
    }

    private static void applyQuota(HttpHeaders headers, QuotaSnapshot quota) {
        if (quota.budget() == null) {
            return;
        }
        headers.set(QUOTA_LIMIT, Long.toString(quota.budget()));
        headers.set(QUOTA_REMAINING, Long.toString(quota.remaining()));
        if (quota.state() == QuotaSnapshot.State.WARNING) {
            // The whole point of a soft threshold: an operator finds out while there is still time
            // to raise the budget, rather than when traffic stops.
            headers.set(QUOTA_WARNING, "monthly token budget %d%% consumed".formatted(percentUsed(quota)));
        }
    }

    private static long percentUsed(QuotaSnapshot quota) {
        return quota.budget() == null ? 0 : Math.min(100, quota.used() * 100 / quota.budget());
    }
}

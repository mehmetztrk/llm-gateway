package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.application.port.in.UsageQueryUseCase;
import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import io.github.mehmetztrk.llmgateway.domain.usage.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * A tenant reading its own usage.
 *
 * <p>The tenant comes from the authenticated key, never from a query parameter. There is no way to
 * ask for someone else's usage because there is nowhere to put the request — the same reason
 * {@code X-Tenant-Id} does nothing on the completions endpoint.
 */
@RestController
@RequestMapping("/v1/usage")
class UsageController {

    private final UsageQueryUseCase usage;
    private final Scheduler blockingScheduler;

    UsageController(UsageQueryUseCase usage, Scheduler blockingScheduler) {
        this.usage = usage;
        this.blockingScheduler = blockingScheduler;
    }

    @GetMapping
    Mono<UsageResponse> summary(
            @AuthenticationPrincipal AuthenticatedCaller caller,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") int limit) {

        // Default window is the last 30 days: long enough to be useful, short enough that the
        // default query cannot accidentally scan a year.
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(30, ChronoUnit.DAYS) : from;

        return Mono.fromCallable(() -> {
                    UsageLedger.UsageSummary summary = usage.summarise(caller.tenantId(), start, end);
                    List<UsageEntry> entries = usage.recent(caller.tenantId(), start, end, limit).stream()
                            .map(record -> new UsageEntry(
                                    record.createdAt(),
                                    record.model(),
                                    record.provider().value(),
                                    record.usage().promptTokens(),
                                    record.usage().completionTokens(),
                                    record.cost().toDecimal(),
                                    record.latency().toMillis(),
                                    record.cacheStatus().wireValue(),
                                    record.streamed()))
                            .toList();

                    return new UsageResponse(
                            start,
                            end,
                            summary.requests(),
                            summary.cachedRequests(),
                            summary.cacheHitRatio(),
                            summary.promptTokens(),
                            summary.completionTokens(),
                            new Money(summary.costMicros(), summary.currency()).toDecimal(),
                            summary.currency(),
                            entries);
                })
                .subscribeOn(blockingScheduler);
    }

    /**
     * @param costNote spelled out in the response, not only in the docs: a number that looks like
     *     money will be read as money, and this one is a counterfactual over reference prices.
     */
    record UsageResponse(
            Instant from,
            Instant to,
            long requests,
            long cachedRequests,
            double cacheHitRatio,
            long promptTokens,
            long completionTokens,
            BigDecimal cost,
            String currency,
            List<UsageEntry> entries) {

        String costNote() {
            return "Cost is computed from a configurable reference price table; locally served "
                    + "models are free. Tokens are measured, cost is derived.";
        }
    }

    record UsageEntry(
            Instant at,
            String model,
            String provider,
            int promptTokens,
            int completionTokens,
            BigDecimal cost,
            long latencyMs,
            String cache,
            boolean streamed) {}
}

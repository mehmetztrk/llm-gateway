package io.github.mehmetztrk.llmgateway.adapter.in.web.security;

import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Resolves {@code Authorization: Bearer <key>} into an {@link ApiKeyAuthentication}.
 *
 * <p><b>The one place blocking JDBC meets the event loop.</b> {@link TenantRepository} is
 * synchronous, so the lookup is wrapped in {@code Mono.fromCallable} and moved onto a
 * virtual-thread scheduler with {@code subscribeOn}. Calling the repository directly here would
 * park a Netty event-loop thread on a database round trip, and a handful of concurrent cache misses
 * would stall every other in-flight request on that loop — the exact failure ADR-0006 exists to
 * prevent. The cache means this hop is rare, but "rare" is not "never", so it is always applied.
 *
 * <p>why {@code Bearer} rather than a custom header: OpenAI SDKs send the key that way and nothing
 * else. {@code X-API-Key} is accepted as well, for curl and for dashboards, but it is secondary.
 */
public class ApiKeyAuthenticationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";
    private static final String API_KEY_HEADER = "X-API-Key";

    private final TenantRepository tenants;
    private final ApiKeyHasher hasher;
    private final Scheduler blockingScheduler;

    public ApiKeyAuthenticationFilter(TenantRepository tenants, ApiKeyHasher hasher, Scheduler blockingScheduler) {
        this.tenants = tenants;
        this.hasher = hasher;
        this.blockingScheduler = blockingScheduler;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Optional<String> presented = extractKey(exchange);
        if (presented.isEmpty()) {
            // No credential is not a failure here — the authorisation rules decide whether the
            // requested path needed one. That keeps /actuator/health reachable without a key.
            return chain.filter(exchange);
        }

        String keyHash = hasher.hash(presented.get());

        return Mono.fromCallable(() -> tenants.findCallerByKeyHash(keyHash))
                .subscribeOn(blockingScheduler)
                .flatMap(maybeCaller -> maybeCaller
                        .map(caller -> authenticate(exchange, chain, caller))
                        .orElseGet(() -> {
                            // why continue the chain instead of throwing: an exception raised here
                            // escapes @RestControllerAdvice entirely — that advice only sees
                            // failures dispatched through a controller — and surfaces as a 500.
                            // Leaving the exchange unauthenticated lets the authorisation rules
                            // reject it, which routes through the configured entry point and
                            // produces a proper 401 in the OpenAI error envelope.
                            //
                            // It also makes an unknown key and a missing key indistinguishable to
                            // the caller, which is the intended behaviour: telling someone their
                            // key "exists but is revoked" confirms the key was real.
                            //
                            // Logged without the key or its digest: a digest in a log is still a
                            // credential-shaped artefact tying a request to a specific key.
                            log.debug("presented API key did not resolve to an active caller");
                            return chain.filter(exchange);
                        }));
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain, AuthenticatedCaller caller) {
        Authentication authentication = new ApiKeyAuthentication(caller);
        exchange.getAttributes().put(AuthenticatedCaller.class.getName(), caller);
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(authentication))));
    }

    private Optional<String> extractKey(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String value = authorization.substring(BEARER.length()).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        String header = headers.getFirst(API_KEY_HEADER);
        return header == null || header.isBlank() ? Optional.empty() : Optional.of(header.trim());
    }
}

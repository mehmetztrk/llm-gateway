package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 429 semantics and the headers a client paces itself with. */
class RateLimitApiIT extends AbstractGatewayIT {

    /** A tenant whose limits this test sets explicitly, so it cannot be affected by defaults. */
    private String tenantKeyWithLimits(int requestsPerMinute, long tokensPerMinute, Long monthlyBudget) {
        JsonNode tenant = asAdmin()
                .post()
                .uri("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"rl-" + UUID.randomUUID() + "\",\"allowedModels\":[\"mock-fast\"]}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        String tenantId = tenant.get("id").asText();

        asAdmin()
                .put()
                .uri("/admin/tenants/" + tenantId + "/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requestsPerMinute\":%d,\"tokensPerMinute\":%d,\"monthlyTokenBudget\":%s}"
                        .formatted(requestsPerMinute, tokensPerMinute, monthlyBudget == null ? "null" : monthlyBudget))
                .exchange()
                .expectStatus()
                .isOk();

        return asAdmin()
                .post()
                .uri("/admin/tenants/" + tenantId + "/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"role\":\"TENANT\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .get("key")
                .asText();
    }

    private WebTestClient.ResponseSpec complete(String key) {
        return withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange();
    }

    @Test
    @DisplayName("a successful response carries the rate-limit headers, not just a 429")
    void successCarriesHeaders() {
        String key = tenantKeyWithLimits(60, 1_000_000, null);

        complete(key)
                .expectStatus()
                .isOk()
                // A client that only learns its budget by being refused has no way to slow down
                // before it is.
                .expectHeader()
                .valueEquals(RateLimitHeaders.LIMIT_REQUESTS, "60")
                .expectHeader()
                .valueEquals(RateLimitHeaders.REMAINING_REQUESTS, "59")
                .expectHeader()
                .exists(RateLimitHeaders.LIMIT_TOKENS)
                .expectHeader()
                .exists(RateLimitHeaders.REMAINING_TOKENS);
    }

    @Test
    @DisplayName("exceeding the request limit returns 429 with Retry-After")
    void requestLimitReturns429() {
        String key = tenantKeyWithLimits(2, 1_000_000, null);

        complete(key).expectStatus().isOk();
        complete(key).expectStatus().isOk();

        EntityExchangeResult<byte[]> refused = complete(key)
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("rate_limit_exceeded")
                .jsonPath("$.error.type")
                .isEqualTo("rate_limit_error")
                .returnResult();

        String retryAfter = refused.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        // A 429 without Retry-After is an invitation to hammer.
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    @Test
    @DisplayName("a single oversized prompt is refused on the token bucket, before any provider call")
    void tokenLimitReturns429() {
        // Requests are plentiful, tokens are not: the two dimensions are independent, and a caller
        // sending few but enormous prompts must still be stopped. The prompt is long enough that
        // the up-front estimate alone exceeds the limit, so this exercises admission rather than
        // settlement.
        String key = tenantKeyWithLimits(1000, 5, null);
        String longPrompt = "x".repeat(400);

        withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"mock-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"" + longPrompt
                        + "\"}]}")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("rate_limit_exceeded");
    }

    @Test
    @DisplayName("one tenant's consumption does not affect another's")
    void bucketsAreScopedPerTenant() {
        String greedy = tenantKeyWithLimits(1, 1_000_000, null);
        String innocent = tenantKeyWithLimits(10, 1_000_000, null);

        complete(greedy).expectStatus().isOk();
        complete(greedy).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        complete(innocent).expectStatus().isOk();
    }

    @Test
    @DisplayName("tokens actually used are charged afterwards, not only the estimate")
    void settlementChargesActualUsage() {
        // This is the two-phase accounting made visible. A short prompt estimates at 1 token, so
        // admission alone would let this run all day against a 40/min budget. The mock actually
        // spends 33, and settlement charges the difference — so the bucket empties after two calls
        // and the third is refused. Without settlement, the third would succeed.
        String key = tenantKeyWithLimits(1000, 40, null);

        complete(key).expectStatus().isOk();
        complete(key).expectStatus().isOk();
        complete(key).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}

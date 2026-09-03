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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Monthly budgets: the soft warning, then the hard block. */
@TestPropertySource(
        properties = {
            // Caching off: these tests repeat identical requests on purpose, and a cache hit means
            // no tokens are spent and no limit is reached — which is the cache working, not this
            // behaviour being broken. Each test should fail for one reason only.
            "gateway.cache.enabled=false"
        })
class QuotaApiIT extends AbstractGatewayIT {

    private String tenantKeyWithBudget(long monthlyBudget, double softThreshold) {
        JsonNode tenant = asAdmin()
                .post()
                .uri("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"quota-" + UUID.randomUUID() + "\",\"allowedModels\":[\"mock-fast\"]}")
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
                .bodyValue("""
                        {"requestsPerMinute":1000,"tokensPerMinute":1000000,
                         "monthlyTokenBudget":%d,"quotaSoftThreshold":%s}
                        """.formatted(monthlyBudget, softThreshold))
                .exchange()
                .expectStatus()
                .isOk();

        return asAdmin()
                .post()
                .uri("/admin/tenants/" + tenantId + "/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"role\":\"TENANT\"}")
                .exchange()
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
    @DisplayName("a tenant under its soft threshold gets no warning")
    void underThresholdIsQuiet() {
        // The mock spends ~35 tokens per call, so one call stays well under 80% of 10 000.
        String key = tenantKeyWithBudget(10_000, 0.8);

        complete(key)
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(RateLimitHeaders.QUOTA_WARNING)
                .expectHeader()
                .valueEquals(RateLimitHeaders.QUOTA_LIMIT, "10000");
    }

    @Test
    @DisplayName("crossing the soft threshold adds a warning header but still serves the request")
    void softThresholdWarnsWithoutRefusing() {
        // Budget 100, threshold 10%: the first call already crosses it. The point of a soft
        // threshold is that the operator hears about it while there is still budget left.
        String key = tenantKeyWithBudget(100, 0.1);

        complete(key).expectStatus().isOk();

        HttpHeaders headers = complete(key)
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(RateLimitHeaders.QUOTA_WARNING)
                .expectBody()
                .returnResult()
                .getResponseHeaders();

        assertThat(headers.getFirst(RateLimitHeaders.QUOTA_WARNING)).contains("budget");
    }

    @Test
    @DisplayName("an exhausted budget is refused with insufficient_quota and no Retry-After")
    void exhaustedBudgetIsRefused() {
        String key = tenantKeyWithBudget(60, 0.9);

        // Each call costs roughly 35 tokens, so the second exhausts a budget of 60.
        complete(key).expectStatus().isOk();
        complete(key).expectStatus().isOk();

        complete(key)
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                // Deliberately absent: telling a client to retry in a minute when the budget resets
                // next month would cost it a month of pointless requests.
                .doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("insufficient_quota");
    }

    @Test
    @DisplayName("a tenant with no budget is never quota-limited and never touches the counter")
    void unlimitedTenantIsUnaffected() {
        // The demo tenant has no monthly budget configured.
        asTenant()
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(RateLimitHeaders.QUOTA_LIMIT);
    }
}

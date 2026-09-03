package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The ledger, and the usage API that reads it back. */
class UsageLedgerIT extends AbstractGatewayIT {

    private record Party(String tenantId, String key) {}

    private Party newTenant(String prefix) {
        JsonNode tenant = asAdmin()
                .post()
                .uri("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"" + prefix + "-" + UUID.randomUUID() + "\",\"allowedModels\":[\"mock-fast\"]}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        String key = asAdmin()
                .post()
                .uri("/admin/tenants/" + tenant.get("id").asText() + "/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"role\":\"TENANT\"}")
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .get("key")
                .asText();

        return new Party(tenant.get("id").asText(), key);
    }

    private void ask(String key, String prompt) {
        withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"model\":\"mock-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}")
                .exchange()
                .expectStatus()
                .isOk();
    }

    private JsonNode usage(String key) {
        return withKey(key)
                .get()
                .uri("/v1/usage")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    @DisplayName("a completed request appears in the tenant's usage with measured tokens and derived cost")
    void requestIsRecorded() {
        Party party = newTenant("ledger");
        ask(party.key(), "record me " + UUID.randomUUID());

        // The ledger is written asynchronously on purpose, so the test waits for it rather than
        // pretending the write was synchronous. Awaitility, not a sleep: the assertion is "this
        // becomes true", and the flush interval is the ledger's business.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode body = usage(party.key());
            assertThat(body.get("requests").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(body.get("completionTokens").asLong()).isGreaterThan(0);
            // Cost is derived from the reference price table, so a priced model must not be free.
            assertThat(body.get("cost").asDouble()).isGreaterThan(0.0);
            assertThat(body.get("currency").asText()).isEqualTo("USD");
        });
    }

    @Test
    @DisplayName("an entry carries everything needed to explain the charge later")
    void entryIsSelfExplanatory() {
        Party party = newTenant("detail");
        ask(party.key(), "explain me " + UUID.randomUUID());

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode entries = usage(party.key()).get("entries");
            assertThat(entries).isNotEmpty();
            JsonNode entry = entries.get(0);
            assertThat(entry.get("model").asText()).isEqualTo("mock-fast");
            assertThat(entry.get("provider").asText()).isNotBlank();
            assertThat(entry.get("cache").asText()).isEqualTo("miss");
            assertThat(entry.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(entry.has("promptTokens")).isTrue();
        });
    }

    @Test
    @DisplayName("a cache hit is recorded at zero cost, which is what makes the saving measurable")
    void cacheHitCostsNothing() {
        Party party = newTenant("cached");
        String prompt = "cache me " + UUID.randomUUID();

        ask(party.key(), prompt);
        ask(party.key(), prompt);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode body = usage(party.key());
            assertThat(body.get("requests").asLong()).isEqualTo(2);
            assertThat(body.get("cachedRequests").asLong()).isEqualTo(1);
            assertThat(body.get("cacheHitRatio").asDouble()).isEqualTo(0.5);

            // Exactly one of the two rows carries a cost.
            long priced = 0;
            for (JsonNode entry : body.get("entries")) {
                if (entry.get("cost").asDouble() > 0) {
                    priced++;
                }
            }
            assertThat(priced).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a tenant sees only its own usage")
    void usageIsTenantIsolated() {
        Party a = newTenant("usage-a");
        Party b = newTenant("usage-b");

        ask(a.key(), "only mine " + UUID.randomUUID());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> usage(a.key()).get("requests").asLong() >= 1);

        // B did nothing, and there is no parameter it could set to see A's rows.
        assertThat(usage(b.key()).get("requests").asLong()).isZero();
    }
}

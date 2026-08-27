package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The tenant and key lifecycle, driven entirely through the public admin API. */
class AdminApiIT extends AbstractGatewayIT {

    private JsonNode createTenant(String name, String models) {
        return asAdmin()
                .post()
                .uri("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"" + name + "\",\"allowedModels\":" + models + "}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode issueKey(String tenantId, String role) {
        return asAdmin()
                .post()
                .uri("/admin/tenants/" + tenantId + "/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"role\":\"" + role + "\",\"label\":\"integration test\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    @DisplayName("a created tenant can be issued a key, and that key immediately works")
    void tenantAndKeyLifecycle() {
        JsonNode tenant = createTenant("acme-" + UUID.randomUUID(), "[\"mock-fast\"]");
        String tenantId = tenant.get("id").asText();
        assertThat(tenant.get("allowedModels")).hasSize(1);

        JsonNode issued = issueKey(tenantId, "TENANT");
        String plaintext = issued.get("key").asText();

        assertThat(plaintext).startsWith("llmgw_");
        assertThat(issued.get("warning").asText()).contains("not retrievable");

        withKey(plaintext)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("listing keys never returns anything that could be used to authenticate")
    void keyListingNeverExposesSecrets() {
        JsonNode tenant = createTenant("secrets-" + UUID.randomUUID(), "[\"mock-fast\"]");
        String tenantId = tenant.get("id").asText();
        String plaintext = issueKey(tenantId, "TENANT").get("key").asText();

        String listing = asAdmin()
                .get()
                .uri("/admin/tenants/" + tenantId + "/keys")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(listing).doesNotContain(plaintext);
        // The stored prefix is short enough to be useless and long enough to identify the key.
        assertThat(listing).contains(plaintext.substring(0, 12));
        assertThat(listing).contains("keyPrefix");
    }

    @Test
    @DisplayName("a revoked key stops working once the cache entry expires")
    void revokedKeyStopsWorking() {
        JsonNode tenant = createTenant("revoke-" + UUID.randomUUID(), "[\"mock-fast\"]");
        String tenantId = tenant.get("id").asText();
        JsonNode issued = issueKey(tenantId, "TENANT");
        String plaintext = issued.get("key").asText();
        String keyId = issued.get("metadata").get("id").asText();

        completion(plaintext).expectStatus().isOk();

        asAdmin().delete().uri("/admin/keys/" + keyId).exchange().expectStatus().isNoContent();

        // Revocation through this instance evicts the cache immediately, so no waiting and no
        // sleeping: if this needed a Thread.sleep, the invalidation would be the thing at fault.
        completion(plaintext).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("revoking twice is not an error")
    void revocationIsIdempotent() {
        JsonNode tenant = createTenant("idem-" + UUID.randomUUID(), "[\"mock-fast\"]");
        String keyId = issueKey(tenant.get("id").asText(), "TENANT")
                .get("metadata")
                .get("id")
                .asText();

        asAdmin().delete().uri("/admin/keys/" + keyId).exchange().expectStatus().isNoContent();
        asAdmin().delete().uri("/admin/keys/" + keyId).exchange().expectStatus().isNoContent();
    }

    @Test
    @DisplayName("a model outside the tenant's allow-list is refused with 403")
    void modelOutsideAllowListIsRefused() {
        JsonNode tenant = createTenant("narrow-" + UUID.randomUUID(), "[\"mock-fast\"]");
        String plaintext =
                issueKey(tenant.get("id").asText(), "TENANT").get("key").asText();

        withKey(plaintext)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-echo","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("model_not_allowed");
    }

    @Test
    @DisplayName("tightening the allow-list takes effect at once, not after the cache TTL")
    void allowListChangesAreNotDelayedByTheCache() {
        JsonNode tenant = createTenant("policy-" + UUID.randomUUID(), "[\"mock-fast\",\"mock-echo\"]");
        String tenantId = tenant.get("id").asText();
        String plaintext = issueKey(tenantId, "TENANT").get("key").asText();

        completion(plaintext, "mock-echo").expectStatus().isOk();

        asAdmin()
                .put()
                .uri("/admin/tenants/" + tenantId + "/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"allowedModels\":[\"mock-fast\"]}")
                .exchange()
                .expectStatus()
                .isOk();

        // A policy change that only took effect after a TTL would be worthless during an incident.
        completion(plaintext, "mock-echo").expectStatus().isForbidden();
    }

    @Test
    @DisplayName("an unknown tenant id is a 404, not a 500")
    void unknownTenantIsNotFound() {
        asAdmin()
                .get()
                .uri("/admin/tenants/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("tenant_not_found");
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec completion(String key) {
        return completion(key, "mock-fast");
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec completion(
            String key, String model) {
        return withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange();
    }
}

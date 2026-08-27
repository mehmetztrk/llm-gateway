package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Zero cross-tenant leakage, proven rather than asserted in prose.
 *
 * <p>This milestone covers identity and policy. The cache-level proof — that tenant A can never
 * read a response cached for tenant B — arrives with M6, and will live beside these tests.
 */
class CrossTenantIsolationIT extends AbstractGatewayIT {

    private record Party(String tenantId, String key) {}

    private Party createParty(String prefix, String models) {
        JsonNode tenant = asAdmin()
                .post()
                .uri("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"" + prefix + "-" + UUID.randomUUID() + "\",\"allowedModels\":" + models + "}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        String tenantId = tenant.get("id").asText();
        String key = asAdmin()
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

        return new Party(tenantId, key);
    }

    @Test
    @DisplayName("one tenant's key cannot use another tenant's permitted model")
    void policiesDoNotBleedAcrossTenants() {
        Party a = createParty("iso-a", "[\"mock-fast\"]");
        Party b = createParty("iso-b", "[\"mock-echo\"]");

        // Each may use its own model.
        complete(a.key(), "mock-fast").expectStatus().isOk();
        complete(b.key(), "mock-echo").expectStatus().isOk();

        // Neither may use the other's, even though the gateway itself serves both.
        complete(a.key(), "mock-echo").expectStatus().isForbidden();
        complete(b.key(), "mock-fast").expectStatus().isForbidden();
    }

    @Test
    @DisplayName("a tenant key cannot read another tenant's keys, because it cannot reach the admin API at all")
    void tenantsCannotEnumerateEachOther() {
        Party a = createParty("enum-a", "[\"mock-fast\"]");
        Party b = createParty("enum-b", "[\"mock-fast\"]");

        withKey(a.key())
                .get()
                .uri("/admin/tenants/" + b.tenantId() + "/keys")
                .exchange()
                .expectStatus()
                .isForbidden();

        withKey(a.key()).get().uri("/admin/tenants").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("revoking one tenant's key leaves the other tenant unaffected")
    void revocationIsScopedToOneTenant() {
        Party a = createParty("rev-a", "[\"mock-fast\"]");
        Party b = createParty("rev-b", "[\"mock-fast\"]");

        String keyId = asAdmin()
                .get()
                .uri("/admin/tenants/" + a.tenantId() + "/keys")
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .get("keys")
                .get(0)
                .get("id")
                .asText();

        asAdmin().delete().uri("/admin/keys/" + keyId).exchange().expectStatus().isNoContent();

        complete(a.key(), "mock-fast").expectStatus().isUnauthorized();
        // The cache is invalidated wholesale on revocation, so this also proves the resulting
        // cold cache re-resolves other tenants correctly rather than locking them out.
        complete(b.key(), "mock-fast").expectStatus().isOk();
    }

    @Test
    @DisplayName("the resolved tenant comes from the key, not from anything the client can set")
    void tenantCannotBeSpoofedByAHeader() {
        Party a = createParty("spoof-a", "[\"mock-fast\"]");
        Party b = createParty("spoof-b", "[\"mock-echo\"]");

        // A client that guesses another tenant's id and sends it explicitly gains nothing: the
        // identity is derived solely from the presented key.
        withKey(a.key())
                .post()
                .uri("/v1/chat/completions")
                .header("X-Tenant-Id", b.tenantId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-echo","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(a.tenantId()).isNotEqualTo(b.tenantId());
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec complete(String key, String model) {
        return withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange();
    }
}

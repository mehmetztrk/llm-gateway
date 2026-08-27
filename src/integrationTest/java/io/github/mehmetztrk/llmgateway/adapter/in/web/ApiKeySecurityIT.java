package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Authentication and authorisation on the request path. */
class ApiKeySecurityIT extends AbstractGatewayIT {

    private static final String BODY = """
            {"model":"mock-fast","messages":[{"role":"user","content":"hi"}]}
            """;

    private WebTestClient.ResponseSpec completionWith(WebTestClient authenticated) {
        return authenticated
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange();
    }

    @Test
    @DisplayName("a request with no key is rejected with an OpenAI-shaped 401")
    void missingKeyIsRejected() {
        completionWith(client)
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                // Not Spring Security's empty body: an SDK must be able to raise a typed
                // AuthenticationError rather than an opaque parse failure.
                .jsonPath("$.error.code")
                .isEqualTo("invalid_api_key")
                .jsonPath("$.error.type")
                .isEqualTo("invalid_request_error");
    }

    @Test
    @DisplayName("an unknown key is rejected exactly as a missing key is, revealing nothing")
    void unknownKeyIsIndistinguishableFromNoKey() {
        // Asserting the two responses are byte-identical is the real property, and it is stronger
        // than pinning the message text: it stays true if the wording is ever reworded, and it
        // fails the moment someone "helpfully" adds "that key has been revoked".
        byte[] withBadKey = completionWith(withKey("llmgw_definitely_not_a_real_key"))
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .returnResult()
                .getResponseBodyContent();

        byte[] withNoKey = completionWith(client)
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .returnResult()
                .getResponseBodyContent();

        org.assertj.core.api.Assertions.assertThat(withBadKey).isEqualTo(withNoKey);
    }

    @Test
    @DisplayName("a valid tenant key is accepted")
    void validTenantKeyIsAccepted() {
        completionWith(asTenant()).expectStatus().isOk();
    }

    @Test
    @DisplayName("the key is also accepted via X-API-Key, for callers that are not an SDK")
    void acceptsXApiKeyHeader() {
        client.post()
                .uri("/v1/chat/completions")
                .header("X-API-Key", TENANT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("Bearer parsing is case-insensitive on the scheme, as RFC 7235 requires")
    void bearerSchemeIsCaseInsensitive() {
        client.post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "bearer " + TENANT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("a tenant key cannot reach the admin API")
    void tenantKeyCannotAdminister() {
        asTenant()
                .get()
                .uri("/admin/tenants")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("insufficient_permissions");
    }

    @Test
    @DisplayName("an admin key can reach the admin API")
    void adminKeyCanAdminister() {
        asAdmin().get().uri("/admin/tenants").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("an admin key can also make completions, since ADMIN is a superset of TENANT")
    void adminKeyCanAlsoComplete() {
        completionWith(asAdmin()).expectStatus().isOk();
    }
}

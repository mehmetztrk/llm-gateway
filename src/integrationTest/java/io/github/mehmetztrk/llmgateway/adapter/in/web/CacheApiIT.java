package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * Exact caching, and the isolation that makes it safe to have at all.
 *
 * <p>The semantic layer is off here: it needs an embedding model, which CI does not have. Its own
 * behaviour is covered by {@code SemanticCacheIT}, which skips when Ollama is absent. The exact
 * layer needs nothing but Redis, so it is tested unconditionally — and it is the layer that carries
 * most of the hit ratio in practice.
 */
@TestPropertySource(properties = {"gateway.cache.enabled=true", "gateway.cache.semantic-enabled=false"})
class CacheApiIT extends AbstractGatewayIT {

    private String tenantKey(String prefix) {
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

        return asAdmin()
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
    }

    private EntityExchangeResult<byte[]> ask(String key, String prompt) {
        return withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"model\":\"mock-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();
    }

    private String cacheHeader(EntityExchangeResult<byte[]> result) {
        return result.getResponseHeaders().getFirst(RateLimitHeaders.CACHE);
    }

    private String contentOf(EntityExchangeResult<byte[]> result) {
        String body = new String(result.getResponseBodyContent());
        int start = body.indexOf("\"content\":\"") + "\"content\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    @DisplayName("the first request misses and the second is an exact hit")
    void repeatedRequestIsServedFromCache() {
        String key = tenantKey("cache");
        String prompt = "what is a gateway " + UUID.randomUUID();

        EntityExchangeResult<byte[]> first = ask(key, prompt);
        EntityExchangeResult<byte[]> second = ask(key, prompt);

        assertThat(cacheHeader(first)).isEqualTo("miss");
        assertThat(cacheHeader(second)).isEqualTo("hit-exact");
        // Byte-identical, not merely both non-empty: a cache that returns a *different* correct
        // answer is not a cache.
        assertThat(contentOf(second)).isEqualTo(contentOf(first));
    }

    @Test
    @DisplayName("a different prompt is a miss, not a false hit")
    void differentPromptMisses() {
        String key = tenantKey("cache");

        ask(key, "first question " + UUID.randomUUID());
        assertThat(cacheHeader(ask(key, "an entirely different question " + UUID.randomUUID())))
                .isEqualTo("miss");
    }

    @Test
    @DisplayName("ZERO CROSS-TENANT LEAKAGE: tenant B never sees tenant A's cached answer")
    void cacheIsTenantIsolated() {
        // The single worst failure this system could have. Both tenants send a byte-identical
        // prompt to the same model; the second must still be a miss.
        String tenantA = tenantKey("iso-a");
        String tenantB = tenantKey("iso-b");
        String identicalPrompt = "exactly the same question " + UUID.randomUUID();

        EntityExchangeResult<byte[]> aFirst = ask(tenantA, identicalPrompt);
        EntityExchangeResult<byte[]> aSecond = ask(tenantA, identicalPrompt);
        EntityExchangeResult<byte[]> bFirst = ask(tenantB, identicalPrompt);

        assertThat(cacheHeader(aFirst)).isEqualTo("miss");
        // A's own repeat hits, which proves the cache is working at all — without this the test
        // would pass even if caching were switched off entirely.
        assertThat(cacheHeader(aSecond)).isEqualTo("hit-exact");
        // B sends the same bytes and still misses.
        assertThat(cacheHeader(bFirst)).isEqualTo("miss");
    }

    @Test
    @DisplayName("the same prompt against a different model is a miss")
    void cacheIsScopedPerModel() {
        // A tenant asking for a larger model must not be served a smaller model's answer: that is
        // a different product at a different price.
        String key = tenantKey("model-scope");
        String prompt = "same words, different model " + UUID.randomUUID();

        withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"model\":\"mock-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}")
                .exchange()
                .expectStatus()
                .isOk();

        // mock-echo is served by the same provider but is a different model name.
        asAdmin()
                .put()
                .uri("/admin/tenants/" + tenantIdOf(key) + "/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"allowedModels\":[\"mock-fast\",\"mock-echo\"]}")
                .exchange()
                .expectStatus()
                .isOk();

        EntityExchangeResult<byte[]> other = withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"model\":\"mock-echo\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();

        assertThat(cacheHeader(other)).isEqualTo("miss");
    }

    @Test
    @DisplayName("a cached answer is replayed as a stream, indistinguishable in shape from a live one")
    void cachedResponseReplaysAsAStream() {
        String key = tenantKey("replay");
        String prompt = "stream me " + UUID.randomUUID();

        // Populate the cache with a non-streamed call.
        ask(key, prompt);

        List<String> frames = withKey(key)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"model\":\"mock-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt
                        + "\"}],\"stream\":true}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(RateLimitHeaders.CACHE, "hit-exact")
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(frames).isNotNull();
        // More than one content frame, and the same [DONE] sentinel: a client rendering
        // incrementally must not be able to tell that this answer came from cache.
        assertThat(frames).hasSizeGreaterThan(2);
        assertThat(frames.getLast()).isEqualTo("[DONE]");
        assertThat(frames.getFirst()).contains("\"role\":\"assistant\"");
    }

    private String tenantIdOf(String key) {
        // The tenant id is not returned to a tenant key, so this reads it back through the admin
        // API by matching on the key prefix.
        JsonNode tenants = asAdmin()
                .get()
                .uri("/admin/tenants")
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        for (JsonNode tenant : tenants.get("tenants")) {
            JsonNode keys = asAdmin()
                    .get()
                    .uri("/admin/tenants/" + tenant.get("id").asText() + "/keys")
                    .exchange()
                    .expectBody(JsonNode.class)
                    .returnResult()
                    .getResponseBody();
            for (JsonNode stored : keys.get("keys")) {
                if (key.startsWith(stored.get("keyPrefix").asText().replace("...", ""))) {
                    return tenant.get("id").asText();
                }
            }
        }
        throw new IllegalStateException("could not resolve the tenant for the issued key");
    }
}

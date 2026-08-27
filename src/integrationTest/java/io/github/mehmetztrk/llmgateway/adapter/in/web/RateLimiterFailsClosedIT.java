package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The behaviour ADR-0004 argues for, proven rather than asserted.
 *
 * <p>Redis is pointed at a port nothing is listening on, which is a faithful stand-in for the real
 * failure: the limiter cannot tell whether a request is within its limit. The gateway <b>refuses</b>
 * — letting the request through would mean an outage of the limiter silently becomes unlimited
 * access to every provider behind the gateway, which is the exact situation limits exist to prevent
 * and one that costs real money the moment a paid provider is configured.
 *
 * <p><b>why this class does not extend {@link AbstractGatewayIT}.</b> The base declares its Redis
 * container with {@code @ServiceConnection}, and connection details from a service connection take
 * precedence over any {@code @TestPropertySource}. A subclass "pointing Redis at a dead port" would
 * therefore quietly keep talking to the real container — the test would pass by testing nothing.
 * That is exactly what the first version of this class did. Wiring the properties by hand is the
 * only way to be sure the failure being asserted is the failure being simulated.
 *
 * <p>Postgres still comes from the shared singleton container, referenced statically so that its
 * initialiser runs.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.security.key-pepper=integration-test-pepper",
            "gateway.security.bootstrap-tenant-key=" + AbstractGatewayIT.TENANT_KEY,
            "gateway.rate-limit.timeout=200ms",
            // Nothing listens here. Not a hostname that fails DNS: a refused connection is the
            // fast, unambiguous failure, and it proves the timeout path is not what is under test.
            "spring.data.redis.host=127.0.0.1",
            "spring.data.redis.port=1"
        })
@AutoConfigureWebTestClient(timeout = "30s")
class RateLimiterFailsClosedIT {

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractGatewayIT.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractGatewayIT.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractGatewayIT.POSTGRES::getPassword);
    }

    @Test
    @DisplayName("with Redis unreachable the request is refused, not silently allowed")
    void failsClosed() {
        client.post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AbstractGatewayIT.TENANT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("rate_limiter_unavailable");
    }

    @Test
    @DisplayName("health stays reachable, so an operator can still see the gateway is up")
    void healthIsUnaffected() {
        // The limiter being down must not make the process look dead to an orchestrator, or a
        // Redis blip turns into a rolling restart of every gateway replica.
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }
}

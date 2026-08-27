package io.github.mehmetztrk.llmgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Smoke test: the context starts and the health endpoint answers.
 *
 * <p>why {@code WebTestClient} with a MOCK environment rather than a real port: it drives the full
 * WebFlux filter chain and handler mapping in-process, so we test routing and serialisation without
 * paying for a TCP listener. Field injection is fine here and only here — the ArchUnit rule
 * deliberately excludes test sources.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class HealthEndpointTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("GET /actuator/health reports UP")
    void healthEndpointReportsUp() {
        webTestClient
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("liveness and readiness probes are exposed for container orchestration")
    void probesAreExposed() {
        webTestClient
                .get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }
}

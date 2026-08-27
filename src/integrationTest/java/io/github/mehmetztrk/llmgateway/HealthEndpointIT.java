package io.github.mehmetztrk.llmgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the context starts against a real database and the probes answer.
 *
 * <p>No credential is presented on purpose. Health and readiness must be reachable without one, or
 * an orchestrator could never distinguish "still starting" from "misconfigured" — and a probe that
 * needs a secret is a probe that fails for the wrong reasons.
 */
class HealthEndpointIT extends AbstractGatewayIT {

    @Test
    @DisplayName("GET /actuator/health reports UP without authentication")
    void healthEndpointReportsUp() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("readiness probe is exposed for container orchestration")
    void probesAreExposed() {
        client.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("everything not explicitly permitted is denied, including unmapped paths")
    void unmappedPathsAreDenied() {
        // anyExchange().denyAll() means a future endpoint added without a matching rule is
        // unreachable rather than accidentally public.
        client.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }
}

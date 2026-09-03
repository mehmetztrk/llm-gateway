package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.MediaType;

/**
 * The metrics a Grafana panel queries.
 *
 * <p>Asserting on the exposed names matters more than it looks: a committed dashboard references
 * them as strings, so renaming a meter breaks the dashboard silently and only in production. This
 * test is the thing that turns that into a build failure.
 *
 * <p><b>why {@code @AutoConfigureObservability}.</b> Spring Boot switches metric and trace export
 * off inside {@code @SpringBootTest} — sensible by default, since most tests do not want an
 * exporter running, but it means {@code /actuator/prometheus} simply does not exist unless a test
 * asks for it. Without this annotation the endpoint 404s and the failure looks like a
 * configuration bug in the application rather than a deliberate test-time default.
 */
@AutoConfigureObservability
class MetricsIT extends AbstractGatewayIT {

    private String scrape() {
        return new String(client.get()
                .uri("/actuator/prometheus")
                .header("Authorization", "Bearer " + ADMIN_KEY)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent());
    }

    @Test
    @DisplayName("the gateway's own meters are exposed with the names the dashboard uses")
    void gatewayMetersAreExposed() {
        asTenant()
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"mock-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"metrics "
                        + UUID.randomUUID() + "\"}]}")
                .exchange()
                .expectStatus()
                .isOk();

        String body = scrape();

        // Exactly the names in docker/grafana/dashboards/llm-gateway-overview.json.
        assertThat(body).contains("llmgw_request_duration_seconds");
        assertThat(body).contains("llmgw_tokens_total");
        assertThat(body).contains("llmgw_cache_lookups_total");
        // Labels the dashboard slices by.
        assertThat(body).contains("model=\"mock-fast\"");
        assertThat(body).contains("direction=\"output\"");
    }

    @Test
    @DisplayName("tenant id is deliberately NOT a metric label")
    void tenantIsNotALabel() {
        // A label with unbounded cardinality creates a time series per tenant and takes the
        // monitoring system down before it tells anyone anything. Per-tenant numbers live in the
        // usage ledger, which is built for that query.
        assertThat(scrape()).doesNotContain("tenant_id=");
    }

    @Test
    @DisplayName("the metrics endpoint is not public")
    void metricsRequireAuthentication() {
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    }
}

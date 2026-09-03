package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The chaos test: the primary provider is dead, and the gateway must keep serving.
 *
 * <p>"Dead" is expressed as {@code error-rate: 1.0} on the primary mock rather than by stopping a
 * container. That is a deliberate trade. Killing a container is more visceral, but it makes the
 * test depend on Docker lifecycle timing, on which container the runner schedules where, and on a
 * cleanup path that must run even when an assertion fails. Configuring a provider to fail every
 * call reproduces the property under test — every request to it errors — deterministically, in CI,
 * on a laptop with no GPU. The container-killing version lives in {@code scripts/demo.sh}, where a
 * human is watching.
 *
 * <p><b>The latency below is measured, not asserted from belief.</b> The milestone target is a
 * failover under two seconds; the test records the actual wall-clock time and fails if it exceeds
 * it.
 */
@TestPropertySource(
        properties = {
            // The primary fails every call, and fails its health probe with it.
            "gateway.providers.mock.error-rate=1.0",
            "gateway.providers.mock-standby.error-rate=0.0",
            "gateway.rate-limit.timeout=2s",
            // Probe often enough that the test does not spend its time waiting for one.
            "gateway.routing.probe-interval=300ms"
        })
class FailoverChaosIT extends AbstractGatewayIT {

    private static final Duration TARGET = Duration.ofSeconds(2);

    private void callHighAvailabilityAlias() {
        asTenant()
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-ha","messages":[{"role":"user","content":"survive this"}]}
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.object")
                .isEqualTo("chat.completion")
                // Not merely a 200: an empty or truncated body would satisfy a status assertion
                // and prove nothing.
                .jsonPath("$.choices[0].message.content")
                .isNotEmpty();
    }

    private String providerStatus() {
        return new String(asAdmin()
                .get()
                .uri("/admin/providers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent());
    }

    @Test
    @DisplayName("with the primary failing every call, the alias is still served")
    void failsOverToStandby() {
        callHighAvailabilityAlias();
    }

    @Test
    @DisplayName("failover completes within the milestone target, measured")
    void failoverIsFastEnough() {
        // Warm the path once so the measurement is of failover, not of first-call class loading
        // and connection setup — measuring the JVM warming up would measure the wrong thing.
        callHighAvailabilityAlias();

        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            callHighAvailabilityAlias();
            millis.add((System.nanoTime() - start) / 1_000_000);
        }

        long worst = millis.stream().mapToLong(Long::longValue).max().orElseThrow();
        long median = millis.stream().sorted().toList().get(millis.size() / 2);

        // Printed so the number appears in the test output and can be quoted honestly; the
        // assertion is what makes it a test rather than a log line.
        System.out.printf(
                "failover latency: median %d ms, worst %d ms (target < %d ms)%n", median, worst, TARGET.toMillis());

        assertThat(worst)
                .as("worst failover took %d ms, target is %d ms", worst, TARGET.toMillis())
                .isLessThan(TARGET.toMillis());
    }

    @Test
    @DisplayName("the probe marks the failing provider down without any user request having to fail")
    void probeMarksFailingProviderDown() {
        // This is the health probe pre-empting failover, and it is the more valuable half of the
        // design: by the time traffic arrives, routing already prefers the provider it has seen
        // answer. The first version of this test assumed user requests would keep hitting the dead
        // provider until it was marked down. They do not — the probe gets there first, which is
        // the whole point of having one.
        //
        // Awaitility rather than a sleep: the assertion is "this becomes true", and how long that
        // takes is the probe interval's business, not the test's.
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = providerStatus();
                    assertThat(status).contains("\"id\":\"mock\",\"health\":\"DOWN\"");
                    assertThat(status).contains("\"id\":\"mock-standby\",\"health\":\"UP\"");
                });
    }

    @Test
    @DisplayName("requests keep succeeding while the primary is down")
    void trafficIsUnaffectedByTheOutage() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .until(() -> providerStatus().contains("\"id\":\"mock\",\"health\":\"DOWN\""));

        // The whole point: an outage of one provider is invisible to callers.
        for (int i = 0; i < 3; i++) {
            callHighAvailabilityAlias();
        }
    }
}

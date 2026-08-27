package io.github.mehmetztrk.llmgateway.adapter.out.provider.mock;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.provider.LlmProviderContract;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

class MockProviderContractTest extends LlmProviderContract {

    // A fixed clock keeps createdAt deterministic, which matters because the contract asserts on
    // fields a real clock would make different on every run.
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Override
    protected LlmProvider provider() {
        return new MockProvider(
                ProviderId.of("mock"),
                new MockProviderProperties(true, Set.of("mock-fast"), Duration.ZERO, 32, 0.0, 42L),
                FIXED);
    }

    @Override
    protected String supportedModel() {
        return "mock-fast";
    }
}

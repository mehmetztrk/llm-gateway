package io.github.mehmetztrk.llmgateway.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mehmetztrk.llmgateway.domain.error.ModelNotAllowed;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelAllowListTest {

    @Test
    @DisplayName("an empty allow-list permits nothing")
    void emptyDeniesEverything() {
        // The safe reading of an ambiguous state. A migration or a failed insert that leaves the
        // list empty must not silently grant access to every model.
        assertThat(ModelAllowList.NONE.permits("mock-fast")).isFalse();
        assertThat(ModelAllowList.NONE.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the wildcard permits any model")
    void wildcardPermitsEverything() {
        assertThat(ModelAllowList.ANY.permits("anything-at-all")).isTrue();
    }

    @Test
    @DisplayName("an explicit list permits only what it lists")
    void explicitListIsExact() {
        ModelAllowList list = ModelAllowList.of("mock-fast");
        assertThat(list.permits("mock-fast")).isTrue();
        assertThat(list.permits("mock-echo")).isFalse();
        assertThat(list.permits("MOCK-FAST"))
                .as("model names are case-sensitive")
                .isFalse();
    }

    @Test
    @DisplayName("a tenant refuses a model outside its list with a typed domain error")
    void tenantEnforcesItsOwnPolicy() {
        Tenant tenant = new Tenant(
                TenantId.random(), "acme", true, ModelAllowList.of("mock-fast"), Instant.parse("2026-01-01T00:00:00Z"));

        tenant.requireModelAllowed("mock-fast");

        assertThatThrownBy(() -> tenant.requireModelAllowed("gpt-4"))
                .isInstanceOf(ModelNotAllowed.class)
                .hasMessageContaining("gpt-4");
    }
}

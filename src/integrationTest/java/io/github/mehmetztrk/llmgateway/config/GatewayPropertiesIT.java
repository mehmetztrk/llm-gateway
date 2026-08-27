package io.github.mehmetztrk.llmgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the privacy default. Prompt logging turning itself on through a careless config change is
 * the kind of regression nobody notices until it is in a log aggregator, so it gets a test.
 */
class GatewayPropertiesIT extends AbstractGatewayIT {

    @Autowired
    private GatewayProperties properties;

    @Test
    @DisplayName("prompt logging is disabled unless explicitly switched on")
    void promptLoggingIsOffByDefault() {
        assertThat(properties.observability().logPrompts()).isFalse();
    }
}

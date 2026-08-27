package io.github.mehmetztrk.llmgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the privacy default. Prompt logging turning itself on through a careless config change is
 * the kind of regression nobody notices until it is in a log aggregator, so it gets a test.
 */
@SpringBootTest
class GatewayPropertiesTest {

    @Autowired
    private GatewayProperties properties;

    @Test
    @DisplayName("prompt logging is disabled unless explicitly switched on")
    void promptLoggingIsOffByDefault() {
        assertThat(properties.observability().logPrompts()).isFalse();
    }
}

package io.github.mehmetztrk.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the LLM gateway.
 *
 * <p>{@code @ConfigurationPropertiesScan} discovers every {@code @ConfigurationProperties} record
 * without each one needing to be registered on a {@code @Configuration} class. This is what lets us
 * keep configuration immutable: records have no setters, so the classic
 * {@code @EnableConfigurationProperties} + JavaBean binding style would not work.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}

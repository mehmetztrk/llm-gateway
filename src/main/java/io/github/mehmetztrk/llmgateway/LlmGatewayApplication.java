package io.github.mehmetztrk.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the LLM gateway.
 *
 * <p>{@code @ConfigurationPropertiesScan} discovers every {@code @ConfigurationProperties} record
 * without each one needing to be registered on a {@code @Configuration} class. This is what lets us
 * keep configuration immutable: records have no setters, so the classic
 * {@code @EnableConfigurationProperties} + JavaBean binding style would not work.
 */
/**
 * why {@code ReactiveUserDetailsServiceAutoConfiguration} is excluded: with
 * spring-boot-starter-security on the classpath and no {@code UserDetailsService} of our own, Spring
 * Boot helpfully creates a default {@code user} account with a random password and prints it at
 * start-up. This gateway authenticates with API keys only, so that account is an authentication
 * path nobody intended, wired up by default, announced in the logs.
 */
@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}

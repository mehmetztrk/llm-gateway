package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.adapter.out.telemetry.GatewayMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TelemetryConfig {

    @Bean
    public GatewayMetrics gatewayMetrics(MeterRegistry registry) {
        return new GatewayMetrics(registry);
    }
}

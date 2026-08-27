package io.github.mehmetztrk.llmgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code gateway.rate-limit}.
 *
 * @param timeout how long to wait for Redis before giving up. Short on purpose: the limiter sits in
 *     front of every request, so a slow Redis must fail fast rather than add its latency to the
 *     whole gateway. Exceeding it means the request is refused, not allowed — see ADR-0004.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(@DefaultValue("250ms") Duration timeout) {

    public RateLimitProperties {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}

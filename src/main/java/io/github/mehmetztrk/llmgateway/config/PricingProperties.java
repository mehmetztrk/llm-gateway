package io.github.mehmetztrk.llmgateway.config;

import io.github.mehmetztrk.llmgateway.domain.usage.PriceTable;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The price table, loaded from {@code pricing.yml}.
 *
 * <p>A separate file rather than more lines in {@code application.yml} because it changes for
 * different reasons and by different people: prices move when a vendor changes them, not when the
 * gateway is reconfigured. Keeping them apart means a price update is a one-file diff that an
 * operator can review without reading application configuration.
 */
@ConfigurationProperties(prefix = "gateway.pricing")
public record PricingProperties(
        @DefaultValue("USD") String currency, @DefaultValue Map<String, ModelPrice> models) {

    public PricingProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    public record ModelPrice(long inputMicrosPer1k, long outputMicrosPer1k) {}

    public PriceTable toPriceTable() {
        return new PriceTable(
                models.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> new PriceTable.ModelPrice(
                                        entry.getValue().inputMicrosPer1k(),
                                        entry.getValue().outputMicrosPer1k()))),
                currency);
    }
}

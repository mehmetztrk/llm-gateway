package io.github.mehmetztrk.llmgateway.domain.usage;

import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import java.util.Map;
import java.util.Optional;

/**
 * What a thousand tokens costs, per model.
 *
 * <p><b>An honesty note that belongs in the code, not only in the README.</b> Every model this
 * gateway serves locally is free — the electricity is real but nothing is billed. The prices here
 * are therefore <em>reference</em> prices, taken from published rates for comparable hosted models,
 * and the cost they produce is a counterfactual: "what this traffic would have cost at these
 * rates".
 *
 * <p>That makes the measured quantity <b>tokens</b>, which are counted, and the derived quantity
 * <b>cost</b>, which is arithmetic over a stated rate. BENCHMARKS.md reports it that way round.
 * Presenting a local model's output as a dollar saving without saying so would be inventing a
 * number, which this project does not do.
 *
 * <p>A model with no entry costs zero rather than failing the request. A missing price is a
 * configuration gap, and refusing to serve traffic over one would be a self-inflicted outage.
 */
public record PriceTable(Map<String, ModelPrice> prices, String currency) {

    public PriceTable {
        prices = Map.copyOf(prices);
    }

    /**
     * @param inputMicrosPer1k cost of a thousand prompt tokens, in micro-units
     * @param outputMicrosPer1k cost of a thousand completion tokens; almost always higher than
     *     input, which is why the two are separate rather than one blended rate
     */
    public record ModelPrice(long inputMicrosPer1k, long outputMicrosPer1k) {}

    public Optional<ModelPrice> priceOf(String model) {
        return Optional.ofNullable(prices.get(model));
    }

    /** Rounds half-up at the micro, so a million small requests do not systematically under-bill. */
    public Money costOf(String model, TokenUsage usage) {
        return priceOf(model)
                .map(price -> new Money(
                        Math.round(usage.promptTokens() * price.inputMicrosPer1k() / 1000.0)
                                + Math.round(usage.completionTokens() * price.outputMicrosPer1k() / 1000.0),
                        currency))
                .orElseGet(() -> Money.zero(currency));
    }
}

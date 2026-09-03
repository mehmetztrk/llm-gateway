package io.github.mehmetztrk.llmgateway.domain.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money, held as integer micro-units.
 *
 * <p>why not {@code double}: a token price is of the order of $0.0000005, and summing millions of
 * those in binary floating point accumulates error until the total disagrees with the sum of its
 * parts. why not {@code BigDecimal} everywhere: it is the right type for presenting money and the
 * wrong one for adding it up a million times on a hot path. Micros give exact arithmetic in a
 * {@code long}, and {@link #toDecimal()} converts once, at the edge, for display.
 */
public record Money(long micros, String currency) {

    private static final BigDecimal MICROS_PER_UNIT = BigDecimal.valueOf(1_000_000L);

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            // Silently adding two currencies is how a billing system produces a number that is
            // wrong in a way nobody can trace.
            throw new IllegalArgumentException("cannot add " + other.currency + " to " + currency);
        }
        return new Money(micros + other.micros, currency);
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(micros).divide(MICROS_PER_UNIT, 6, RoundingMode.HALF_UP);
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency;
    }
}

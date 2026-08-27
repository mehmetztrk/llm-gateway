package io.github.mehmetztrk.llmgateway.domain.limits;

import java.util.Optional;

/**
 * A tenant's monthly token budget.
 *
 * @param monthlyTokenBudget null means unlimited. Deliberately nullable rather than encoded as 0 or
 *     -1: a sentinel that reads as "block everything" to anyone who has not checked the convention
 *     is how an unlimited tenant ends up locked out.
 * @param softThresholdFraction the fraction of the budget at which responses start carrying a
 *     warning header, giving an operator time to act before anything is refused
 */
public record QuotaPolicy(Long monthlyTokenBudget, double softThresholdFraction) {

    public static final QuotaPolicy UNLIMITED = new QuotaPolicy(null, 0.8);

    public QuotaPolicy {
        if (monthlyTokenBudget != null && monthlyTokenBudget <= 0) {
            throw new IllegalArgumentException("monthlyTokenBudget must be positive when present");
        }
        if (softThresholdFraction <= 0 || softThresholdFraction > 1) {
            throw new IllegalArgumentException("softThresholdFraction must be within (0, 1]");
        }
    }

    public boolean isUnlimited() {
        return monthlyTokenBudget == null;
    }

    public Optional<Long> budget() {
        return Optional.ofNullable(monthlyTokenBudget);
    }

    public long softThresholdTokens() {
        return monthlyTokenBudget == null ? Long.MAX_VALUE : (long) (monthlyTokenBudget * softThresholdFraction);
    }
}

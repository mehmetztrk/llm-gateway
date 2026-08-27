package io.github.mehmetztrk.llmgateway.domain.limits;

/**
 * Where a tenant stands against its monthly budget.
 *
 * <p>The three states exist because "you are out of budget" is a terrible thing to learn for the
 * first time when your traffic stops. {@link State#WARNING} is what makes the hard block
 * unsurprising.
 */
public record QuotaSnapshot(State state, long used, Long budget) {

    public enum State {
        /** Under the soft threshold; nothing to say. */
        OK,
        /** Past the soft threshold but under budget: served, with a warning header. */
        WARNING,
        /** Budget exhausted: refused. */
        EXCEEDED
    }

    public static final QuotaSnapshot UNLIMITED = new QuotaSnapshot(State.OK, 0, null);

    public boolean isExceeded() {
        return state == State.EXCEEDED;
    }

    public long remaining() {
        return budget == null ? Long.MAX_VALUE : Math.max(0, budget - used);
    }

    public static QuotaSnapshot evaluate(QuotaPolicy policy, long used) {
        if (policy.isUnlimited()) {
            return new QuotaSnapshot(State.OK, used, null);
        }
        long budget = policy.monthlyTokenBudget();
        State state = used >= budget ? State.EXCEEDED : used >= policy.softThresholdTokens() ? State.WARNING : State.OK;
        return new QuotaSnapshot(state, used, budget);
    }
}

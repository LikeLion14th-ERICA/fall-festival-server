package dev.espero.festival.account;

import java.util.Objects;

/** Validated desired account data held only while the operator command runs. */
public record OperationalAccountChange(
    OperationalAccountState state,
    String bankName,
    String accountNumber,
    String accountHolder,
    String transferLinkUrl
) {

    public OperationalAccountChange {
        Objects.requireNonNull(state, "Operational account state is required");
        boolean configured = state == OperationalAccountState.CONFIGURED;
        if (configured != (bankName != null && accountNumber != null && accountHolder != null)) {
            throw new IllegalArgumentException("Operational account state and values do not agree");
        }
        if (!configured && transferLinkUrl != null) {
            throw new IllegalArgumentException("An unconfigured account cannot have a transfer link");
        }
    }

    public static OperationalAccountChange unconfigured() {
        return new OperationalAccountChange(OperationalAccountState.UNCONFIGURED, null, null, null, null);
    }

    @Override
    public String toString() {
        return "OperationalAccountChange[state=" + state + ", values=[REDACTED]]";
    }
}

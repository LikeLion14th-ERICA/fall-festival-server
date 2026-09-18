package dev.espero.festival.account;

import java.time.Instant;
import java.util.UUID;

/**
 * Current account configuration. Its {@link #toString()} deliberately never
 * reveals account, holder, bank, or transfer-link values.
 */
public record OperationalAccountSetting(
    UUID festivalId,
    OperationalAccountPurpose purpose,
    OperationalAccountState state,
    long version,
    String bankName,
    String accountNumber,
    String accountHolder,
    String transferLinkUrl,
    Instant updatedAt,
    String spaceId,
    String bankCode,
    boolean tossLinkEnabled
) {

    /** A festival-wide TICKET or GOODS setting. */
    public OperationalAccountSetting(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        OperationalAccountState state,
        long version,
        String bankName,
        String accountNumber,
        String accountHolder,
        String transferLinkUrl,
        Instant updatedAt
    ) {
        this(festivalId, purpose, state, version, bankName, accountNumber, accountHolder, transferLinkUrl,
            updatedAt, null, null, false);
    }

    public OperationalAccountSetting {
        if (festivalId == null || purpose == null || state == null || version < 1 || updatedAt == null) {
            throw new IllegalArgumentException("Operational account setting is incomplete");
        }
        boolean configured = state == OperationalAccountState.CONFIGURED;
        if (configured != (bankName != null && accountNumber != null && accountHolder != null)) {
            throw new IllegalArgumentException("Operational account state and values do not agree");
        }
        if (!configured && (transferLinkUrl != null || bankCode != null || tossLinkEnabled)) {
            throw new IllegalArgumentException("An unconfigured account cannot have transfer details");
        }
        if ((purpose == OperationalAccountPurpose.SPACE) != (spaceId != null)) {
            throw new IllegalArgumentException("Only a SPACE account belongs to a booth");
        }
    }

    public OperationalAccountTarget target() {
        return new OperationalAccountTarget(festivalId, purpose, spaceId);
    }

    public boolean isConfigured() {
        return state == OperationalAccountState.CONFIGURED;
    }

    public String accountLastFour() {
        if (!isConfigured()) {
            return null;
        }
        String digits = accountNumber.replaceAll("\\D", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }

    @Override
    public String toString() {
        return "OperationalAccountSetting[festivalId=" + festivalId + ", purpose=" + purpose
            + ", spaceId=" + spaceId + ", state=" + state + ", version=" + version + ", values=[REDACTED], updatedAt="
            + updatedAt + "]";
    }
}

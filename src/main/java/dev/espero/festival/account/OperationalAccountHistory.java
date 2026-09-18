package dev.espero.festival.account;

import java.time.Instant;
import java.util.UUID;

/** A trigger-created history row selected only by the account-operator workflow. */
public record OperationalAccountHistory(
    long id,
    UUID festivalId,
    OperationalAccountPurpose purpose,
    long version,
    OperationalAccountState afterState,
    String afterBankName,
    String afterAccountNumber,
    String afterAccountHolder,
    String afterTransferLinkUrl,
    Instant occurredAt,
    String afterBankCode,
    Boolean afterTossLinkEnabled
) {

    public boolean isRestorable() {
        return afterState != null;
    }

    public OperationalAccountChange restoredChange() {
        if (!isRestorable()) {
            throw new OperationalAccountException("ACCOUNT_HISTORY_VERSION_NOT_RESTORABLE");
        }
        return new OperationalAccountChange(
            afterState, afterBankName, afterAccountNumber, afterAccountHolder, afterTransferLinkUrl,
            afterBankCode, Boolean.TRUE.equals(afterTossLinkEnabled)
        );
    }

    @Override
    public String toString() {
        return "OperationalAccountHistory[id=" + id + ", festivalId=" + festivalId + ", purpose="
            + purpose + ", version=" + version + ", after=[REDACTED], occurredAt=" + occurredAt + "]";
    }
}

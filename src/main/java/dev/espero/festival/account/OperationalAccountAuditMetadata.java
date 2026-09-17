package dev.espero.festival.account;

/** Non-financial context recorded by the database history trigger. */
public record OperationalAccountAuditMetadata(String actor, String reason, String evidenceId) {

    public OperationalAccountAuditMetadata {
        actor = required(actor, "ACCOUNT_ACTOR_INVALID", 100);
        reason = required(reason, "ACCOUNT_REASON_INVALID", 500);
        evidenceId = required(evidenceId, "ACCOUNT_EVIDENCE_ID_INVALID", 128);
    }

    private static String required(String value, String code, int maximumLength) {
        if (value == null) {
            throw new OperationalAccountException(code);
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength
            || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new OperationalAccountException(code);
        }
        return normalized;
    }
}

package dev.espero.festival.account;

/**
 * Operational account uses. TICKET and GOODS are festival-wide; SPACE is one
 * booth's receiving account and is always paired with the booth's API id.
 */
public enum OperationalAccountPurpose {
    TICKET,
    GOODS,
    SPACE;

    public static OperationalAccountPurpose parse(String value) {
        if (value == null) {
            throw new OperationalAccountException("ACCOUNT_PURPOSE_INVALID");
        }
        try {
            return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new OperationalAccountException("ACCOUNT_PURPOSE_INVALID");
        }
    }
}

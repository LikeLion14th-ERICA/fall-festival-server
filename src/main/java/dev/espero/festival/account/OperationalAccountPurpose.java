package dev.espero.festival.account;

/** The only operational account uses supported by the service. */
public enum OperationalAccountPurpose {
    TICKET,
    GOODS;

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

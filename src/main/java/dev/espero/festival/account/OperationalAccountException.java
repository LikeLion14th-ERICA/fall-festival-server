package dev.espero.festival.account;

/** A safe, code-only failure from the account settings workflow. */
public class OperationalAccountException extends RuntimeException {

    private final String code;

    public OperationalAccountException(String code) {
        super(code);
        this.code = code;
    }

    public OperationalAccountException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

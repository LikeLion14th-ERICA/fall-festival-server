package dev.espero.festival.idempotency;

import java.util.regex.Pattern;

/** Header value rules shared by all administrator mutations that opt into idempotency. */
public final class IdempotencyKeyPolicy {

    private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private IdempotencyKeyPolicy() {}

    public static boolean isValid(String key) {
        return key != null && PATTERN.matcher(key).matches();
    }
}

package dev.espero.festival.web;

import java.util.regex.Pattern;

public final class RequestIdPolicy {

    private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private RequestIdPolicy() {}

    public static boolean isValid(String requestId) {
        return requestId != null && PATTERN.matcher(requestId).matches();
    }
}

package dev.espero.festival.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

final class Sha256 {

    private Sha256() {}

    static String hex(String value) {
        Objects.requireNonNull(value, "Hash input is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte byteValue : digest) {
                encoded.append(Character.forDigit((byteValue >>> 4) & 0xF, 16));
                encoded.append(Character.forDigit(byteValue & 0xF, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    static String parts(String namespace, String... values) {
        StringBuilder input = new StringBuilder(namespace).append('|');
        for (String value : values) {
            String safe = value == null ? "<null>" : value;
            input.append(safe.length()).append(':').append(safe).append('|');
        }
        return hex(input.toString());
    }
}

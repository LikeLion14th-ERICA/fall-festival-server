package dev.espero.festival.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks the on-site stamp reward code, a six-digit number the booth staff
 * type on the visitor's phone. The server holds only SHA-256 hashes
 * of the code ({@code STAMP_RECEIPT_CODE_SHA256}, comma-separated so an old
 * and a new code can overlap while it is rotated); the code itself is never
 * stored, logged or returned.
 */
@Component
public class StampReceiptVerifier {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

    private final List<byte[]> hashes;

    public StampReceiptVerifier(@Value("${festival.stamp-receipt.code-sha256:}") String configured) {
        this.hashes = parse(configured);
    }

    static List<byte[]> parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(configured.split(","))
            .map(value -> value.strip().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .map(value -> {
                if (!SHA256_HEX.matcher(value).matches()) {
                    throw new IllegalStateException("STAMP_RECEIPT_CODE_SHA256 must be SHA-256 hex values");
                }
                return HexFormat.of().parseHex(value);
            })
            .toList();
    }

    public boolean configured() {
        return !hashes.isEmpty();
    }

    /** Compares in constant time against every configured hash. */
    public boolean matches(String code) {
        if (code == null) {
            return false;
        }
        String normalized = code.strip();
        if (!CODE.matcher(normalized).matches()) {
            return false;
        }
        byte[] candidate = sha256(normalized);
        boolean matched = false;
        for (byte[] hash : hashes) {
            matched |= MessageDigest.isEqual(hash, candidate);
        }
        return matched;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

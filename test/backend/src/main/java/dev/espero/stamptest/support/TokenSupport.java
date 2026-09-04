package dev.espero.stamptest.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import dev.espero.stamptest.config.SecurityProperties;
import org.springframework.stereotype.Component;

@Component
public class TokenSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecurityProperties properties;

    public TokenSupport(SecurityProperties properties) {
        this.properties = properties;
    }

    public String randomToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormatSupport.toHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String sessionTokenHash(String value) {
        return HexFormatSupport.toHex(hmac("session:" + value));
    }

    public String ackToken(UUID notificationId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac("ack:" + notificationId));
    }

    public boolean validAckToken(UUID notificationId, String supplied) {
        if (supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
            ackToken(notificationId).getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII)
        );
    }

    public boolean constantTimeEquals(String supplied, String expected) {
        if (supplied == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
            hexToBytes(sha256(supplied)),
            hexToBytes(sha256(expected))
        );
    }

    public boolean hashMatches(String rawValue, String expectedHash) {
        if (rawValue == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hexToBytes(sessionTokenHash(rawValue)), hexToBytes(expectedHash));
    }

    public boolean configured() {
        return properties.tokenPepper().getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private byte[] hmac(String value) {
        if (!configured()) {
            throw new IllegalStateException("APP_TOKEN_PEPPER must contain at least 32 UTF-8 bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                properties.tokenPepper().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
            ));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private byte[] hexToBytes(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }
}

package dev.espero.festival.workbench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** A random token that exists only for the lifetime of one workbench process. */
class WorkbenchSession {

    private final String token;

    WorkbenchSession() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String token() {
        return token;
    }

    boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.US_ASCII),
            candidate.getBytes(StandardCharsets.US_ASCII)
        );
    }
}

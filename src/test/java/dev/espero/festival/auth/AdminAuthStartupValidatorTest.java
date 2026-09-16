package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminAuthStartupValidatorTest {

    @Test
    void acceptsStrongSecretAndExplicitOrigin() {
        assertThatCode(() -> new AdminAuthStartupValidator(properties(
            "strong-admin-jwt-signing-secret-at-least-32-bytes", "https://admin.example.com"
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrWeakSigningSecret() {
        assertThatThrownBy(() -> new AdminAuthStartupValidator(properties("short", "https://admin.example.com")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADMIN_JWT_SIGNING_SECRET");
    }

    @Test
    void rejectsWildcardOrNonOriginCorsConfiguration() {
        assertThatThrownBy(() -> new AdminAuthStartupValidator(properties(
            "strong-admin-jwt-signing-secret-at-least-32-bytes", "*"
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_ALLOWED_ORIGIN");
        assertThatThrownBy(() -> new AdminAuthStartupValidator(properties(
            "strong-admin-jwt-signing-secret-at-least-32-bytes", "https://admin.example.com/path"
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_ALLOWED_ORIGIN");
    }

    private AdminAuthProperties properties(String secret, String origin) {
        return new AdminAuthProperties(Duration.ofMinutes(15), Duration.ofDays(7), secret, origin, null, null);
    }
}

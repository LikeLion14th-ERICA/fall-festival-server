package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import dev.espero.festival.domain.AdminAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminTokenServiceTest {

    private static final Instant NOW = Instant.parse("2030-10-01T03:00:00Z");
    private static final String SECRET = "test-admin-jwt-signing-secret-at-least-32-bytes";
    private static final UUID ADMIN_ID = UUID.fromString("0eb57656-71f4-4a08-92ec-557034bb6571");

    @Test
    void issuesAndVerifiesMinimalAccessToken() throws Exception {
        AdminTokenService service = serviceAt(NOW, SECRET);

        AdminTokenService.AccessToken token = service.issueAccessToken(account());
        AdminTokenService.AccessClaims claims = service.verifyAccessToken(token.value()).orElseThrow();

        assertThat(claims.adminId()).isEqualTo(ADMIN_ID);
        assertThat(claims.authority()).isEqualTo("ADMIN");
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(SignedJWT.parse(token.value()).getJWTClaimsSet().getClaims())
            .containsOnlyKeys("sub", "authority", "iat", "exp")
            .doesNotContainValue("admin-user");
    }

    @Test
    void rejectsExpiredAccessToken() {
        String token = serviceAt(NOW, SECRET).issueAccessToken(account()).value();

        assertThat(serviceAt(NOW.plus(Duration.ofMinutes(16)), SECRET).verifyAccessToken(token)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = serviceAt(NOW, SECRET).issueAccessToken(account()).value();

        assertThat(serviceAt(NOW, "different-test-admin-jwt-signing-secret-32-bytes").verifyAccessToken(token)).isEmpty();
    }

    @Test
    void rejectsTamperedToken() {
        AdminTokenService service = serviceAt(NOW, SECRET);
        String token = service.issueAccessToken(account()).value();
        String[] parts = token.split("\\.");
        parts[1] = (parts[1].charAt(0) == 'a' ? "b" : "a") + parts[1].substring(1);

        assertThat(service.verifyAccessToken(String.join(".", parts))).isEmpty();
    }

    @Test
    void createsOpaqueRefreshTokensAndStableSha256Hashes() {
        AdminTokenService service = serviceAt(NOW, SECRET);

        String first = service.newRefreshToken();
        String second = service.newRefreshToken();

        assertThat(first).hasSize(43).isNotEqualTo(second);
        assertThat(service.hashRefreshToken(first)).hasSize(64).isNotEqualTo(first);
        assertThat(service.hashRefreshToken(first)).isEqualTo(service.hashRefreshToken(first));
    }

    private AdminTokenService serviceAt(Instant instant, String secret) {
        return new AdminTokenService(
            new AdminAuthProperties(Duration.ofMinutes(15), Duration.ofDays(7), secret, null, null, null),
            Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private AdminAccount account() {
        return new AdminAccount(ADMIN_ID, "admin-user", "hash", "ADMIN", true, NOW, NOW, null);
    }
}

package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.domain.AdminRefreshSession;
import dev.espero.festival.persistence.AdminAuthStore;
import dev.espero.festival.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    private static final Instant NOW = Instant.parse("2030-10-01T03:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("f65c4986-70c5-4d8e-aee4-24a845686999");

    @Mock
    private AdminAuthStore store;

    private BCryptPasswordEncoder passwordEncoder;
    private AdminTokenService tokenService;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        AdminAuthProperties properties = new AdminAuthProperties(
            Duration.ofMinutes(15), Duration.ofDays(7),
            "test-admin-jwt-signing-secret-at-least-32-bytes", null, null, null
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        tokenService = new AdminTokenService(properties, clock);
        service = new AdminAuthService(store, passwordEncoder, tokenService, properties, clock);
    }

    @Test
    void logsInAndStoresOnlyRefreshTokenHash() {
        when(store.findAccountByUsername("admin")).thenReturn(Optional.of(account(true)));

        AdminAuthService.SessionResult result = service.login(" admin ", "correct-password");

        assertThat(tokenService.verifyAccessToken(result.accessToken().value())).isPresent();
        ArgumentCaptor<AdminRefreshSession> session = ArgumentCaptor.forClass(AdminRefreshSession.class);
        verify(store).insertRefreshSession(session.capture());
        assertThat(session.getValue().tokenHash()).isEqualTo(tokenService.hashRefreshToken(result.refreshToken()));
        assertThat(session.getValue().tokenHash()).isNotEqualTo(result.refreshToken());
        verify(store).updateLastLogin(ADMIN_ID, NOW);
    }

    @Test
    void rejectsUnknownUsername() {
        when(store.findAccountByUsername("missing")).thenReturn(Optional.empty());

        assertAuthenticationFailure(() -> service.login("missing", "correct-password"));
        verify(store, never()).insertRefreshSession(any());
    }

    @Test
    void verifiesPasswordAgainstDummyHashForUnknownUsername() {
        PasswordEncoder verifyingEncoder = mock(PasswordEncoder.class);
        when(store.findAccountByUsername("missing")).thenReturn(Optional.empty());
        when(verifyingEncoder.matches("candidate-password", AdminAuthService.DUMMY_PASSWORD_HASH))
            .thenReturn(false);
        AdminAuthProperties properties = new AdminAuthProperties(
            Duration.ofMinutes(15), Duration.ofDays(7),
            "test-admin-jwt-signing-secret-at-least-32-bytes", null, null, null
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminAuthService serviceWithVerifyingEncoder = new AdminAuthService(
            store, verifyingEncoder, new AdminTokenService(properties, clock), properties, clock
        );

        assertAuthenticationFailure(() -> serviceWithVerifyingEncoder.login("missing", "candidate-password"));

        verify(verifyingEncoder).matches("candidate-password", AdminAuthService.DUMMY_PASSWORD_HASH);
    }

    @Test
    void rejectsWrongPassword() {
        when(store.findAccountByUsername("admin")).thenReturn(Optional.of(account(true)));

        assertAuthenticationFailure(() -> service.login("admin", "wrong-password"));
    }

    @Test
    void verifiesPasswordBeforeRejectingDisabledAccount() {
        AdminAccount disabledAccount = account(false);
        PasswordEncoder verifyingEncoder = mock(PasswordEncoder.class);
        when(store.findAccountByUsername("admin")).thenReturn(Optional.of(disabledAccount));
        when(verifyingEncoder.matches("correct-password", disabledAccount.passwordHash())).thenReturn(true);
        AdminAuthProperties properties = new AdminAuthProperties(
            Duration.ofMinutes(15), Duration.ofDays(7),
            "test-admin-jwt-signing-secret-at-least-32-bytes", null, null, null
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminAuthService serviceWithVerifyingEncoder = new AdminAuthService(
            store, verifyingEncoder, new AdminTokenService(properties, clock), properties, clock
        );

        assertAuthenticationFailure(() -> serviceWithVerifyingEncoder.login("admin", "correct-password"));

        verify(verifyingEncoder).matches("correct-password", disabledAccount.passwordHash());
    }

    @Test
    void rotatesValidRefreshToken() {
        String raw = "valid-refresh-token";
        AdminRefreshSession old = refreshSession(raw, NOW.plusSeconds(60), null);
        when(store.findRefreshSessionForUpdate(old.tokenHash())).thenReturn(Optional.of(old));
        when(store.findAccountById(ADMIN_ID)).thenReturn(Optional.of(account(true)));
        when(store.revokeRefreshSession(old.id(), NOW)).thenReturn(true);

        AdminAuthService.SessionResult result = service.refresh(raw);

        assertThat(result.refreshToken()).isNotEqualTo(raw);
        verify(store).revokeRefreshSession(old.id(), NOW);
        verify(store).insertRefreshSession(any(AdminRefreshSession.class));
    }

    @Test
    void rejectsRevokedRefreshTokenReuse() {
        String raw = "revoked-refresh-token";
        AdminRefreshSession old = refreshSession(raw, NOW.plusSeconds(60), NOW.minusSeconds(1));
        when(store.findRefreshSessionForUpdate(old.tokenHash())).thenReturn(Optional.of(old));

        assertRefreshFailure(() -> service.refresh(raw));
    }

    @Test
    void rejectsExpiredRefreshToken() {
        String raw = "expired-refresh-token";
        AdminRefreshSession old = refreshSession(raw, NOW, null);
        when(store.findRefreshSessionForUpdate(old.tokenHash())).thenReturn(Optional.of(old));

        assertRefreshFailure(() -> service.refresh(raw));
    }

    @Test
    void rejectsUnknownRefreshToken() {
        String raw = "unknown-refresh-token";
        when(store.findRefreshSessionForUpdate(tokenService.hashRefreshToken(raw))).thenReturn(Optional.empty());

        assertRefreshFailure(() -> service.refresh(raw));
    }

    @Test
    void rejectsRefreshForDisabledAccount() {
        String raw = "disabled-refresh-token";
        AdminRefreshSession old = refreshSession(raw, NOW.plusSeconds(60), null);
        when(store.findRefreshSessionForUpdate(old.tokenHash())).thenReturn(Optional.of(old));
        when(store.findAccountById(ADMIN_ID)).thenReturn(Optional.of(account(false)));

        assertRefreshFailure(() -> service.refresh(raw));
    }

    @Test
    void logoutRevokesOnlyTheCurrentAdminsRefreshSession() {
        service.logout("logout-refresh-token", new AdminPrincipal(ADMIN_ID, "admin", "ADMIN"));

        verify(store).revokeRefreshSessionForAdmin(
            tokenService.hashRefreshToken("logout-refresh-token"), ADMIN_ID, NOW
        );
    }

    @Test
    void secretBearingAuthTypesRedactTheirStringRepresentations() {
        String signingSecret = "raw-signing-secret-that-must-not-appear";
        String bootstrapPassword = "raw-bootstrap-password-that-must-not-appear";
        String accessToken = "raw-access-token-that-must-not-appear";
        String refreshToken = "raw-refresh-token-that-must-not-appear";
        String passwordHash = "raw-password-hash-that-must-not-appear";
        AdminAuthProperties properties = new AdminAuthProperties(
            Duration.ofMinutes(15), Duration.ofDays(7), signingSecret,
            "https://admin.test.invalid", "admin", bootstrapPassword
        );
        AdminAccount account = new AdminAccount(
            ADMIN_ID, "admin", passwordHash, "ADMIN", true, NOW, NOW, null
        );
        AdminTokenService.AccessToken issuedAccessToken = new AdminTokenService.AccessToken(
            accessToken, NOW.plusSeconds(900)
        );
        AdminAuthService.SessionResult result = new AdminAuthService.SessionResult(
            account, issuedAccessToken, refreshToken, NOW.plusSeconds(604800)
        );
        String rendered = properties + " " + account + " " + issuedAccessToken + " " + result;

        assertThat(rendered)
            .contains("[REDACTED]")
            .doesNotContain(signingSecret, bootstrapPassword, accessToken, refreshToken, passwordHash);
    }

    private AdminAccount account(boolean enabled) {
        return new AdminAccount(
            ADMIN_ID, "admin", passwordEncoder.encode("correct-password"), "ADMIN", enabled,
            NOW.minusSeconds(60), NOW.minusSeconds(60), null
        );
    }

    private AdminRefreshSession refreshSession(String raw, Instant expiresAt, Instant revokedAt) {
        return new AdminRefreshSession(
            UUID.randomUUID(), ADMIN_ID, tokenService.hashRefreshToken(raw), expiresAt, revokedAt, NOW.minusSeconds(60)
        );
    }

    private void assertAuthenticationFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status().value()).isEqualTo(401);
                assertThat(error.code()).isEqualTo("ADMIN_AUTHENTICATION_FAILED");
            });
    }

    private void assertRefreshFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status().value()).isEqualTo(401);
                assertThat(error.code()).isEqualTo("ADMIN_REFRESH_TOKEN_INVALID");
            });
        verify(store, never()).insertRefreshSession(any());
    }
}

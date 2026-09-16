package dev.espero.festival.auth;

import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.domain.AdminRefreshSession;
import dev.espero.festival.persistence.AdminAuthStore;
import dev.espero.festival.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class AdminAuthService {

    static final String DUMMY_PASSWORD_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AdminAuthStore store;
    private final PasswordEncoder passwordEncoder;
    private final AdminTokenService tokenService;
    private final AdminAuthProperties properties;
    private final Clock clock;

    public AdminAuthService(
        AdminAuthStore store,
        PasswordEncoder passwordEncoder,
        AdminTokenService tokenService,
        AdminAuthProperties properties,
        Clock clock
    ) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public SessionResult login(String username, String password) {
        String normalizedUsername = username == null ? "" : username.strip();
        AdminAccount account = store.findAccountByUsername(normalizedUsername).orElse(null);
        String passwordHash = account == null ? DUMMY_PASSWORD_HASH : account.passwordHash();
        boolean matches = passwordMatches(password, passwordHash);
        if (account == null || !account.enabled() || !matches) {
            throw authenticationFailed();
        }

        Instant now = clock.instant();
        store.updateLastLogin(account.id(), now);
        AdminAccount signedIn = new AdminAccount(
            account.id(), account.username(), account.passwordHash(), account.authority(), account.enabled(),
            account.createdAt(), now, now
        );
        return createSession(signedIn, now);
    }

    @Transactional
    public SessionResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidRefreshToken();
        }
        Instant now = clock.instant();
        AdminRefreshSession session = store.findRefreshSessionForUpdate(tokenService.hashRefreshToken(refreshToken))
            .orElseThrow(this::invalidRefreshToken);
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
            throw invalidRefreshToken();
        }
        AdminAccount account = store.findAccountById(session.adminId())
            .filter(AdminAccount::enabled)
            .orElseThrow(this::invalidRefreshToken);
        if (!store.revokeRefreshSession(session.id(), now)) {
            throw invalidRefreshToken();
        }
        return createSession(account, now);
    }

    @Transactional
    public void logout(String refreshToken, AdminPrincipal principal) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        store.revokeRefreshSessionForAdmin(
            tokenService.hashRefreshToken(refreshToken), principal.adminId(), clock.instant()
        );
    }

    private SessionResult createSession(AdminAccount account, Instant now) {
        AdminTokenService.AccessToken accessToken = tokenService.issueAccessToken(account);
        String refreshToken = tokenService.newRefreshToken();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        store.insertRefreshSession(new AdminRefreshSession(
            UUID.randomUUID(), account.id(), tokenService.hashRefreshToken(refreshToken),
            refreshExpiresAt, null, now
        ));
        return new SessionResult(account, accessToken, refreshToken, refreshExpiresAt);
    }

    private ApiException authenticationFailed() {
        return new ApiException(
            HttpStatus.UNAUTHORIZED, "ADMIN_AUTHENTICATION_FAILED", "관리자 인증에 실패했습니다.", false
        );
    }

    private boolean passwordMatches(String password, String passwordHash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        try {
            return passwordEncoder.matches(password, passwordHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private ApiException invalidRefreshToken() {
        return new ApiException(
            HttpStatus.UNAUTHORIZED, "ADMIN_REFRESH_TOKEN_INVALID", "관리자 세션을 갱신할 수 없습니다.", false
        );
    }

    public record SessionResult(
        AdminAccount account,
        AdminTokenService.AccessToken accessToken,
        String refreshToken,
        Instant refreshExpiresAt
    ) {
        @Override
        public String toString() {
            return "SessionResult[account=" + account.id()
                + ", accessToken=[REDACTED], refreshToken=[REDACTED], refreshExpiresAt="
                + refreshExpiresAt + "]";
        }
    }
}

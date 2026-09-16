package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuthService;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.domain.AdminAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@RequestMapping("/api/v2/admin")
public class AdminSessionController {

    static final String REFRESH_COOKIE_NAME = "__Host-festival-admin-refresh";
    private static final ZoneId FESTIVAL_ZONE = ZoneId.of("Asia/Seoul");

    private final AdminAuthService authService;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;

    public AdminSessionController(AdminAuthService authService, ApiMetaSupport metaSupport, Clock clock) {
        this.authService = authService;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    @PostMapping("/sessions")
    ApiResponse<SessionResponse> login(
        @Valid @RequestBody LoginRequest loginRequest,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AdminAuthService.SessionResult result = authService.login(loginRequest.username(), loginRequest.password());
        setRefreshCookie(response, result.refreshToken(), result.refreshExpiresAt());
        return new ApiResponse<>(SessionResponse.from(result), metaSupport.systemMeta(request, "ko"));
    }

    @PostMapping("/sessions/refresh")
    ApiResponse<SessionResponse> refresh(
        @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AdminAuthService.SessionResult result = authService.refresh(refreshToken);
        setRefreshCookie(response, result.refreshToken(), result.refreshExpiresAt());
        return new ApiResponse<>(SessionResponse.from(result), metaSupport.systemMeta(request, "ko"));
    }

    @DeleteMapping("/sessions/current")
    ApiResponse<LogoutResponse> logout(
        @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
        @AuthenticationPrincipal AdminPrincipal principal,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        authService.logout(refreshToken, principal);
        expireRefreshCookie(response);
        return new ApiResponse<>(new LogoutResponse(true), metaSupport.systemMeta(request, "ko"));
    }

    @GetMapping("/me")
    ApiResponse<AdminResponse> me(
        @AuthenticationPrincipal AdminPrincipal principal,
        HttpServletRequest request
    ) {
        return new ApiResponse<>(
            new AdminResponse(principal.adminId(), principal.username(), principal.authority(), true),
            metaSupport.systemMeta(request, "ko")
        );
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken, Instant expiresAt) {
        long maxAge = Math.max(0, Duration.between(clock.instant(), expiresAt).toSeconds());
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void expireRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    record LoginRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 200) String password
    ) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=[REDACTED]]";
        }
    }

    public record SessionResponse(String accessToken, OffsetDateTime expiresAt, AdminResponse admin) {
        static SessionResponse from(AdminAuthService.SessionResult result) {
            return new SessionResponse(
                result.accessToken().value(),
                OffsetDateTime.ofInstant(result.accessToken().expiresAt(), FESTIVAL_ZONE),
                AdminResponse.from(result.account())
            );
        }

        @Override
        public String toString() {
            return "SessionResponse[accessToken=[REDACTED], expiresAt=" + expiresAt
                + ", admin=" + admin + "]";
        }
    }

    public record AdminResponse(UUID id, String username, String authority, boolean enabled) {
        static AdminResponse from(AdminAccount account) {
            return new AdminResponse(account.id(), account.username(), account.authority(), account.enabled());
        }
    }

    public record LogoutResponse(boolean loggedOut) {}
}

package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.auth.AdminAuthService;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.auth.AdminTokenService;
import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminSessionControllerTest {

    private static final Instant NOW = Instant.parse("2030-10-01T03:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("e205273e-0ea0-4734-a4a9-58b28a69998f");

    private AdminAuthService service;
    private AdminSessionController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = mock(AdminAuthService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        controller = new AdminSessionController(service, ApiMetaTestFixtures.systemMetaSupport(clock), clock);
        request = new MockHttpServletRequest();
    }

    @Test
    void loginReturnsAccessTokenAndSecureHttpOnlyRefreshCookie() {
        when(service.login("admin", "password")).thenReturn(sessionResult());
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiResponse<AdminSessionController.SessionResponse> result = controller.login(
            new AdminSessionController.LoginRequest("admin", "password"), request, response
        );

        assertThat(result.data().accessToken()).isEqualTo("access-token");
        assertThat(result.meta().revision()).isZero();
        assertRefreshCookie(response.getHeader(HttpHeaders.SET_COOKIE), false);
    }

    @Test
    void refreshRotatesTheCookie() {
        when(service.refresh("old-refresh")).thenReturn(sessionResult());
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiResponse<AdminSessionController.SessionResponse> result =
            controller.refresh("old-refresh", request, response);

        verify(service).refresh("old-refresh");
        assertRefreshCookie(response.getHeader(HttpHeaders.SET_COOKIE), false);
        assertThat(result.meta().revision()).isZero();
    }

    @Test
    void logoutRevokesSessionAndExpiresCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin", "ADMIN");

        ApiResponse<AdminSessionController.LogoutResponse> result =
            controller.logout("refresh-token", principal, request, response);

        verify(service).logout("refresh-token", principal);
        assertRefreshCookie(response.getHeader(HttpHeaders.SET_COOKIE), true);
        assertThat(result.meta().revision()).isZero();
    }

    @Test
    void meUsesAuthenticatedPrincipalWithoutParsingJwt() {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin", "ADMIN");

        ApiResponse<AdminSessionController.AdminResponse> response = controller.me(principal, request);
        AdminSessionController.AdminResponse result = response.data();

        assertThat(result.id()).isEqualTo(ADMIN_ID);
        assertThat(result.username()).isEqualTo("admin");
        assertThat(result.authority()).isEqualTo("ADMIN");
        assertThat(response.meta().revision()).isZero();
    }

    @Test
    void requestAndResponseStringRepresentationsRedactSecrets() {
        String password = "raw-password-that-must-not-appear";
        String accessToken = "raw-access-token-that-must-not-appear";
        AdminSessionController.LoginRequest login = new AdminSessionController.LoginRequest("admin", password);
        AdminSessionController.SessionResponse session = new AdminSessionController.SessionResponse(
            accessToken,
            java.time.OffsetDateTime.ofInstant(NOW.plusSeconds(900), ZoneOffset.UTC),
            new AdminSessionController.AdminResponse(ADMIN_ID, "admin", "ADMIN", true)
        );

        assertThat(login.toString()).contains("password=[REDACTED]").doesNotContain(password);
        assertThat(session.toString()).contains("accessToken=[REDACTED]").doesNotContain(accessToken);
    }

    private AdminAuthService.SessionResult sessionResult() {
        AdminAccount account = new AdminAccount(ADMIN_ID, "admin", "hash", "ADMIN", true, NOW, NOW, NOW);
        return new AdminAuthService.SessionResult(
            account,
            new AdminTokenService.AccessToken("access-token", NOW.plusSeconds(900)),
            "new-refresh-token",
            NOW.plusSeconds(604800)
        );
    }

    private void assertRefreshCookie(String cookie, boolean expired) {
        assertThat(cookie)
            .contains(AdminSessionController.REFRESH_COOKIE_NAME + "=")
            .contains("Path=/")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=Strict")
            .doesNotContain("Domain=");
        if (expired) {
            assertThat(cookie).contains("Max-Age=0");
        } else {
            assertThat(cookie).contains("Max-Age=604800");
        }
    }
}

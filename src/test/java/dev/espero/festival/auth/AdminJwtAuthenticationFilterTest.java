package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.persistence.AdminAuthStore;
import dev.espero.festival.web.ApiMetaSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class AdminJwtAuthenticationFilterTest {

    private static final Instant NOW = Instant.parse("2030-10-01T03:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("8f7d42aa-cef4-4072-bb08-e792169b6c69");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesEnabledAdminFromValidBearerToken() throws Exception {
        AdminAccount account = account(true);
        AdminAuthStore store = mock(AdminAuthStore.class);
        when(store.findAccountById(ADMIN_ID)).thenReturn(Optional.of(account));
        AdminTokenService tokens = tokens();
        String jwt = tokens.issueAccessToken(account).value();
        MockHttpServletRequest request = adminRequest(jwt);
        MockFilterChain chain = new MockFilterChain();

        filter(tokens, store).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
            .isEqualTo(new AdminPrincipal(ADMIN_ID, "admin", "ADMIN"));
    }

    @Test
    void rejectsDisabledAdminEvenWithValidJwt() throws Exception {
        AdminAccount enabledAtIssue = account(true);
        AdminAuthStore store = mock(AdminAuthStore.class);
        when(store.findAccountById(ADMIN_ID)).thenReturn(Optional.of(account(false)));
        AdminTokenService tokens = tokens();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(tokens, store).doFilter(adminRequest(tokens.issueAccessToken(enabledAtIssue).value()), response,
            new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void returnsApiEnvelopeWhenAccountLookupIsUnavailable() throws Exception {
        AdminAccount account = account(true);
        AdminAuthStore store = mock(AdminAuthStore.class);
        when(store.findAccountById(ADMIN_ID)).thenThrow(
            new DataAccessResourceFailureException("sensitive SQL connection detail")
        );
        AdminTokenService tokens = tokens();
        MockHttpServletRequest request = adminRequest(tokens.issueAccessToken(account).value());
        request.addHeader("X-Request-Id", "auth-db-unavailable-request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(tokens, store).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("auth-db-unavailable-request");
        assertThat(response.getContentAsString())
            .contains(
                "SERVICE_UNAVAILABLE", "auth-db-unavailable-request", "\"error\"", "\"meta\"",
                "\"retryable\":true"
            )
            .doesNotContain("sensitive SQL connection detail", "Bearer ", "stackTrace");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private AdminJwtAuthenticationFilter filter(AdminTokenService tokens, AdminAuthStore store) {
        ApiSecurityErrorWriter writer = new ApiSecurityErrorWriter(new ObjectMapper(), new ApiMetaSupport(clock()));
        return new AdminJwtAuthenticationFilter(tokens, store, writer);
    }

    private AdminTokenService tokens() {
        return new AdminTokenService(new AdminAuthProperties(
            Duration.ofMinutes(15), Duration.ofDays(7),
            "test-admin-jwt-signing-secret-at-least-32-bytes", null, null, null
        ), clock());
    }

    private Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private AdminAccount account(boolean enabled) {
        return new AdminAccount(ADMIN_ID, "admin", "hash", "ADMIN", enabled, NOW, NOW, null);
    }

    private MockHttpServletRequest adminRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/admin/me");
        request.setRequestURI("/api/v2/admin/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}

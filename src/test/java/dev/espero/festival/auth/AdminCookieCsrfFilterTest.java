package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.web.ApiMetaSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class AdminCookieCsrfFilterTest {

    private static final String ORIGIN = "https://admin.test.invalid";

    @Test
    void acceptsExactConfiguredOriginForCookieEndpoint() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v2/admin/sessions/refresh", ORIGIN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(ORIGIN).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingOriginForCookieEndpoint() throws Exception {
        assertRejected("POST", "/api/v2/admin/sessions/refresh", null, ORIGIN);
    }

    @Test
    void rejectsDifferentOriginForLoginRefreshAndLogout() throws Exception {
        for (String[] requestSpec : new String[][] {
            {"POST", "/api/v2/admin/sessions"},
            {"POST", "/api/v2/admin/sessions/refresh"},
            {"DELETE", "/api/v2/admin/sessions/current"}
        }) {
            assertRejected(requestSpec[0], requestSpec[1], "https://attacker.invalid", ORIGIN);
        }
    }

    @Test
    void rejectsSuffixPrefixPortAndSchemeOriginDifferences() throws Exception {
        String allowedOrigin = "https://admin.example.com";
        for (String requestOrigin : new String[] {
            "https://admin.example.com.attacker.com",
            "https://prefix.admin.example.com",
            "https://admin.example.com:8443",
            "http://admin.example.com"
        }) {
            assertRejected("POST", "/api/v2/admin/sessions/refresh", requestOrigin, allowedOrigin);
        }
    }

    @Test
    void doesNotApplyOriginCheckToBearerProtectedAdminReads() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v2/admin/me", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(ORIGIN).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private AdminCookieCsrfFilter filter(String allowedOrigin) {
        Clock clock = Clock.fixed(Instant.parse("2030-10-01T03:00:00Z"), ZoneOffset.UTC);
        AdminAuthProperties properties = new AdminAuthProperties(
            Duration.ofMinutes(15), Duration.ofDays(7), "test-admin-jwt-signing-secret-at-least-32-bytes",
            allowedOrigin, null, null
        );
        ApiSecurityErrorWriter writer = new ApiSecurityErrorWriter(new ObjectMapper(), new ApiMetaSupport(clock));
        return new AdminCookieCsrfFilter(properties, writer);
    }

    private void assertRejected(String method, String path, String origin, String allowedOrigin) throws Exception {
        MockHttpServletRequest request = request(method, path, origin);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(allowedOrigin).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_CSRF_INVALID");
    }

    private MockHttpServletRequest request(String method, String path, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }
}

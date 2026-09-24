package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

class AdminCorsConfigurationTest {

    @Test
    void allowsConcurrencyHeadersAndExposesConditionalResponseHeaders() {
        SecurityConfiguration configuration = new SecurityConfiguration();
        CorsConfiguration cors = configuration.corsConfigurationSource(properties())
            .getCorsConfiguration(adminRequest());

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedHeaders()).containsExactly(
            "Authorization", "Content-Type", "X-Request-Id", "If-Match", "If-None-Match", "Idempotency-Key"
        );
        assertThat(cors.getExposedHeaders()).containsExactly(
            "X-Request-Id", "Retry-After", "Location", "ETag", "X-Server-Time"
        );
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:3001");
        assertThat(cors.getAllowCredentials()).isTrue();
    }

    @Test
    void loveLetterOriginAllowsCredentialedNestedRoutesAndCsrfHeader() {
        SecurityConfiguration configuration = new SecurityConfiguration();
        var source = configuration.corsConfigurationSource(properties(), "http://localhost:5173");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v2/love-letter-results/123/open");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        CorsConfiguration cors = source.getCorsConfiguration(request);
        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(cors.getAllowedHeaders()).contains("X-Love-Letter-CSRF", "Idempotency-Key");
        assertThat(cors.getAllowCredentials()).isTrue();
        MockHttpServletRequest registration = new MockHttpServletRequest("OPTIONS", "/api/v2/love-letters");
        registration.addHeader("Origin", "http://localhost:5173");
        registration.addHeader("Access-Control-Request-Method", "POST");
        assertThat(source.getCorsConfiguration(registration)).isNotNull();
    }

    private AdminAuthProperties properties() {
        return new AdminAuthProperties(
            Duration.ofMinutes(15),
            Duration.ofDays(7),
            "strong-admin-jwt-signing-secret-at-least-32-bytes",
            "http://localhost:3001",
            null,
            null
        );
    }

    private MockHttpServletRequest adminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v2/admin/crowding");
        request.addHeader("Origin", "http://localhost:3001");
        request.addHeader("Access-Control-Request-Method", "PUT");
        return request;
    }
}

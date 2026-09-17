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
            "Authorization", "Content-Type", "X-Request-Id", "If-Match", "Idempotency-Key"
        );
        assertThat(cors.getExposedHeaders()).containsExactly("ETag", "X-Request-Id", "X-Server-Time");
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:3001");
        assertThat(cors.getAllowCredentials()).isTrue();
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

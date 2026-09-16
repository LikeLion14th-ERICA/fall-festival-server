package dev.espero.festival.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
class AdminAuthStartupValidator {

    private static final int MINIMUM_HS256_SECRET_BYTES = 32;

    AdminAuthStartupValidator(AdminAuthProperties properties) {
        if (properties.accessTokenTtl() == null || properties.accessTokenTtl().isNegative()
            || properties.accessTokenTtl().isZero()
            || properties.refreshTokenTtl() == null || properties.refreshTokenTtl().isNegative()
            || properties.refreshTokenTtl().isZero()) {
            throw new IllegalStateException("Administrator token TTL values must be positive");
        }
        String secret = properties.jwtSigningSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_HS256_SECRET_BYTES) {
            throw new IllegalStateException("ADMIN_JWT_SIGNING_SECRET must contain at least 32 UTF-8 bytes");
        }

        String allowedOrigin = properties.allowedOrigin();
        if (allowedOrigin == null || allowedOrigin.isBlank() || allowedOrigin.contains("*")) {
            throw new IllegalStateException("ADMIN_ALLOWED_ORIGIN must be one explicit HTTP(S) origin");
        }
        URI origin = URI.create(allowedOrigin);
        if (!("https".equalsIgnoreCase(origin.getScheme()) || "http".equalsIgnoreCase(origin.getScheme()))
            || origin.getHost() == null || origin.getPath() == null || !origin.getPath().isEmpty()
            || origin.getQuery() != null || origin.getFragment() != null || origin.getUserInfo() != null) {
            throw new IllegalStateException("ADMIN_ALLOWED_ORIGIN must be one explicit HTTP(S) origin");
        }

        String bootstrapUsername = properties.bootstrapUsername();
        String bootstrapPassword = properties.bootstrapPassword();
        if (bootstrapUsername != null && !bootstrapUsername.isBlank()
            && bootstrapPassword != null && !bootstrapPassword.isBlank()) {
            if (bootstrapUsername.strip().length() > 100) {
                throw new IllegalStateException("ADMIN_BOOTSTRAP_USERNAME is too long");
            }
            if (bootstrapPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD exceeds the BCrypt input limit");
            }
        }
    }
}

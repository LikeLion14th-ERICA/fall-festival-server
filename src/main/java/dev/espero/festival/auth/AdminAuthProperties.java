package dev.espero.festival.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "festival.admin-auth")
public record AdminAuthProperties(
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    String jwtSigningSecret,
    String allowedOrigin,
    String bootstrapUsername,
    String bootstrapPassword
) {
    @Override
    public String toString() {
        return "AdminAuthProperties[accessTokenTtl=" + accessTokenTtl
            + ", refreshTokenTtl=" + refreshTokenTtl
            + ", jwtSigningSecret=[REDACTED], allowedOrigin=" + allowedOrigin
            + ", bootstrapUsername=" + bootstrapUsername
            + ", bootstrapPassword=[REDACTED]]";
    }
}

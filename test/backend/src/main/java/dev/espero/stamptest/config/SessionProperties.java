package dev.espero.stamptest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.session")
public record SessionProperties(Duration ttl, boolean cookieSecure, Duration cleanupDelay) {

    public SessionProperties {
        ttl = ttl == null ? Duration.ofDays(7) : ttl;
        cleanupDelay = cleanupDelay == null ? Duration.ofHours(1) : cleanupDelay;
    }

    public String cookieName() {
        return cookieSecure ? "__Host-stamp_sid" : "stamp_sid";
    }
}

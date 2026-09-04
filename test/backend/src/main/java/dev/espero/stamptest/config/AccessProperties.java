package dev.espero.stamptest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.access")
public record AccessProperties(String code, int maxFailures, Duration window) {

    public AccessProperties {
        code = code == null ? "" : code;
        maxFailures = maxFailures <= 0 ? 5 : maxFailures;
        window = window == null ? Duration.ofMinutes(15) : window;
    }
}

package dev.espero.stamptest.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record SecurityProperties(
    List<String> allowedOrigins,
    String tokenPepper,
    int ackMaxRequests,
    Duration ackWindow
) {

    public SecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
        tokenPepper = tokenPepper == null ? "" : tokenPepper;
        ackMaxRequests = ackMaxRequests <= 0 ? 60 : ackMaxRequests;
        ackWindow = ackWindow == null ? Duration.ofMinutes(1) : ackWindow;
    }
}

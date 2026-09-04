package dev.espero.stamptest.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.push")
public record PushProperties(
    boolean enabled,
    String publicKey,
    String privateKey,
    String subject,
    List<String> allowedEndpointHostSuffixes,
    Duration ttl,
    int maxAttempts,
    Duration workerDelay,
    Duration requestTimeout
) {

    public PushProperties {
        publicKey = publicKey == null ? "" : publicKey.trim();
        privateKey = privateKey == null ? "" : privateKey.trim();
        subject = subject == null ? "" : subject.trim();
        allowedEndpointHostSuffixes = allowedEndpointHostSuffixes == null
            ? List.of()
            : allowedEndpointHostSuffixes.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
        ttl = ttl == null ? Duration.ofSeconds(70) : ttl;
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        workerDelay = workerDelay == null ? Duration.ofSeconds(5) : workerDelay;
        requestTimeout = requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
            ? Duration.ofSeconds(15)
            : requestTimeout;
    }

    public boolean configured() {
        return enabled && !publicKey.isBlank() && !privateKey.isBlank() && !subject.isBlank();
    }
}

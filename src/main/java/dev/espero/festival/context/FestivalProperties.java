package dev.espero.festival.context;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "festival")
public record FestivalProperties(String id) {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    public UUID configuredFestivalId() {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("FESTIVAL_ID must be configured as a UUID");
        }
        String normalized = id.strip();
        if (!UUID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException("FESTIVAL_ID must be configured as a UUID");
        }
        return UUID.fromString(normalized);
    }
}

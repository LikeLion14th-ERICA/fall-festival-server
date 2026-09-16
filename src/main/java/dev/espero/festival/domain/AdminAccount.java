package dev.espero.festival.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminAccount(
    UUID id,
    String username,
    String passwordHash,
    String authority,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    Instant lastLoginAt
) {
    @Override
    public String toString() {
        return "AdminAccount[id=" + id + ", username=" + username
            + ", passwordHash=[REDACTED], authority=" + authority + ", enabled=" + enabled
            + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
            + ", lastLoginAt=" + lastLoginAt + "]";
    }
}

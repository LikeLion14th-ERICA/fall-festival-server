package dev.espero.festival.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminRefreshSession(
    UUID id,
    UUID adminId,
    String tokenHash,
    Instant expiresAt,
    Instant revokedAt,
    Instant createdAt
) {}

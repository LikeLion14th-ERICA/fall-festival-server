package dev.espero.festival.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditEvent(
    UUID id,
    UUID adminId,
    AdminAuditAction action,
    AdminAuditResourceType resourceType,
    String resourceId,
    Instant occurredAt,
    String requestId
) {}

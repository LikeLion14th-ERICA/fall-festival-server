package dev.espero.festival.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Effective published-day hours; null updatedAt identifies catalog fallback. */
public record CrowdingOperatingHours(
    LocalDate operatingDate,
    OffsetDateTime opensAt,
    OffsetDateTime closesAt,
    Instant updatedAt
) {}

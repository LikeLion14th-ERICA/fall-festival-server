package dev.espero.festival.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CrowdingResponse(
    LocalDate operatingDay,
    OffsetDateTime opensAt,
    OffsetDateTime closesAt,
    Status status,
    String savedLevel,
    String colorToken,
    String message,
    OffsetDateTime updatedAt,
    TimeBasis timeBasis
) {

    public enum Status {
        BEFORE_OPEN,
        RELAXED,
        MODERATE,
        CROWDED,
        FULL,
        CLOSED
    }

    public enum TimeBasis {
        NONE,
        OPENING,
        OPERATOR
    }
}

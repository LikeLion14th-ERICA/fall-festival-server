package dev.espero.festival.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CrowdingResponse(
    LocalDate operatingDay,
    OffsetDateTime opensAt,
    OffsetDateTime closesAt,
    OperatingStatus operatingStatus,
    Status status,
    String savedLevel,
    String colorToken,
    String message,
    OffsetDateTime updatedAt,
    TimeBasis timeBasis
) {

    public enum OperatingStatus {
        BEFORE_OPEN,
        OPEN,
        CLOSED
    }

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

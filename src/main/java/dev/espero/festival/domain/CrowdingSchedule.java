package dev.espero.festival.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A validated operating window copied from the published FestivalDay snapshot. */
public record CrowdingSchedule(
    LocalDate operatingDate,
    OffsetDateTime opensAt,
    OffsetDateTime closesAt
) {}

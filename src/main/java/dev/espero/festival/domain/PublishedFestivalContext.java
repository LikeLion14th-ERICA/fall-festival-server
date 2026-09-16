package dev.espero.festival.domain;

import java.time.ZoneId;
import java.util.UUID;

public record PublishedFestivalContext(
    UUID festivalId,
    UUID festivalRevisionId,
    long revisionNumber,
    ZoneId timezone
) {}

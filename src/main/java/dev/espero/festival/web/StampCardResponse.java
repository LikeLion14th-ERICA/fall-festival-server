package dev.espero.festival.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Today's stamp card of the calling browser (STAMP-001). */
public record StampCardResponse(
    LocalDate date,
    int dailyLimit,
    List<Stamp> stamps,
    boolean rewardClaimed
) {

    public StampCardResponse {
        stamps = List.copyOf(stamps);
    }

    /** {@code boothName} is null when the booth is no longer in the published catalog. */
    public record Stamp(String boothId, String boothName, OffsetDateTime collectedAt) {}
}

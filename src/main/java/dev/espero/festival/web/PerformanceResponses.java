package dev.espero.festival.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** API v2 response shapes for lineup and artist detail. */
public final class PerformanceResponses {

    private PerformanceResponses() {}

    public record Image(String url, String alt, int width, int height) {}

    public record Link(String label, String url, String target) {}

    public record LineupItem(
        String artistId,
        String performanceId,
        String name,
        Image image,
        int order
    ) {}

    public record Lineup(LocalDate date, String category, List<LineupItem> items) {}

    public record ArtistPerformance(
        String id,
        LocalDate date,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {}

    public record PerformanceArtist(String id, String name) {}

    public record PerformanceItem(
        String id,
        LocalDate date,
        String title,
        List<PerformanceArtist> artists,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String description
    ) {}

    public record TimetableAxis(String startTime, String endTime) {}

    public record Timetable(
        List<LocalDate> dates,
        TimetableAxis axis,
        List<PerformanceItem> items
    ) {}

    public record ProhibitedItems(List<String> items, String message) {}

    public record Artist(
        String id,
        String category,
        String name,
        Image image,
        String introduction,
        List<Link> socialLinks,
        List<Link> songs,
        List<ArtistPerformance> performances
    ) {}
}

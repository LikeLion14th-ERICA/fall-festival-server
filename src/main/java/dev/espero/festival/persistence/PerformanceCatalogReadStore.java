package dev.espero.festival.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Revision-scoped JDBC reads for the public performance catalog. */
@Repository
@Profile("db")
public class PerformanceCatalogReadStore {

    private final NamedParameterJdbcTemplate jdbc;

    public PerformanceCatalogReadStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LocalDate> festivalDates(UUID festivalRevisionId) {
        return jdbc.query("""
            SELECT festival_date
            FROM festival_days
            WHERE festival_revision_id = :festivalRevisionId
            ORDER BY festival_date
            """, parameters(festivalRevisionId),
            (resultSet, rowNumber) -> resultSet.getObject("festival_date", LocalDate.class)
        );
    }

    public List<LineupItem> lineup(
        UUID festivalRevisionId,
        LocalDate festivalDate,
        String category,
        String locale
    ) {
        return jdbc.query("""
            SELECT p.id AS performance_id,
                   a.id AS artist_id, a.category,
                   a.image_url, a.image_width, a.image_height,
                   translation.locale AS translation_locale,
                   translation.name, translation.image_alt
            FROM performances p
            JOIN performance_artists relation
              ON relation.festival_revision_id = p.festival_revision_id
             AND relation.performance_id = p.id
            JOIN artists a
              ON a.festival_revision_id = relation.festival_revision_id
             AND a.id = relation.artist_id
            LEFT JOIN artist_translations translation
              ON translation.festival_revision_id = a.festival_revision_id
             AND translation.artist_id = a.id
             AND translation.locale = :locale
            WHERE p.festival_revision_id = :festivalRevisionId
              AND p.festival_date = :festivalDate
              AND a.category = :category
            ORDER BY p.starts_at, p.id, relation.display_order, a.id
            """, parameters(festivalRevisionId)
                .addValue("festivalDate", festivalDate)
                .addValue("category", category)
                .addValue("locale", locale),
            (resultSet, rowNumber) -> mapLineupItem(resultSet)
        );
    }

    @Transactional(readOnly = true)
    public Timetable timetable(UUID festivalRevisionId, String locale) {
        List<LocalDate> dates = festivalDates(festivalRevisionId);
        if (dates.isEmpty()) {
            throw new CatalogIntegrityException("Published timetable has no FestivalDay.");
        }

        List<TimetableAxis> axes = jdbc.query("""
            SELECT axis_start_time, axis_end_time
            FROM timetable_configs
            WHERE festival_revision_id = :festivalRevisionId
            """, parameters(festivalRevisionId),
            (resultSet, rowNumber) -> new TimetableAxis(
                resultSet.getObject("axis_start_time", LocalTime.class),
                resultSet.getObject("axis_end_time", LocalTime.class)
            )
        );
        if (axes.size() != 1) {
            throw new CatalogIntegrityException("Published timetable must have exactly one configuration.");
        }

        List<PerformanceHeader> headers = jdbc.query("""
            SELECT performance.id, performance.festival_date,
                   performance.starts_at, performance.ends_at,
                   translation.locale AS translation_locale,
                   translation.title, translation.description
            FROM performances performance
            LEFT JOIN performance_translations translation
              ON translation.festival_revision_id = performance.festival_revision_id
             AND translation.performance_id = performance.id
             AND translation.locale = :locale
            WHERE performance.festival_revision_id = :festivalRevisionId
            ORDER BY performance.festival_date, performance.starts_at, performance.id
            """, parameters(festivalRevisionId).addValue("locale", locale),
            (resultSet, rowNumber) -> mapPerformanceHeader(resultSet)
        );
        Map<String, List<PerformanceArtist>> artists = loadAllPerformanceArtists(festivalRevisionId, locale);
        return new Timetable(
            dates,
            axes.getFirst(),
            headers.stream().map(header -> performance(header, artists.getOrDefault(header.id(), List.of()))).toList()
        );
    }

    @Transactional(readOnly = true)
    public Optional<PerformanceItem> findPerformance(
        UUID festivalRevisionId,
        String performanceId,
        String locale
    ) {
        List<PerformanceHeader> headers = jdbc.query("""
            SELECT performance.id, performance.festival_date,
                   performance.starts_at, performance.ends_at,
                   translation.locale AS translation_locale,
                   translation.title, translation.description
            FROM performances performance
            LEFT JOIN performance_translations translation
              ON translation.festival_revision_id = performance.festival_revision_id
             AND translation.performance_id = performance.id
             AND translation.locale = :locale
            WHERE performance.festival_revision_id = :festivalRevisionId
              AND performance.id = :performanceId
            """, parameters(festivalRevisionId)
                .addValue("performanceId", performanceId)
                .addValue("locale", locale),
            (resultSet, rowNumber) -> mapPerformanceHeader(resultSet)
        );
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        List<PerformanceArtist> artists = loadPerformanceArtists(
            festivalRevisionId, performanceId, locale
        );
        return Optional.of(performance(headers.getFirst(), artists));
    }

    @Transactional(readOnly = true)
    public ProhibitedItems prohibitedItems(UUID festivalRevisionId, String locale) {
        List<String> items = jdbc.query("""
            SELECT item.id, translation.locale AS translation_locale, translation.label
            FROM prohibited_items item
            LEFT JOIN prohibited_item_translations translation
              ON translation.festival_revision_id = item.festival_revision_id
             AND translation.item_id = item.id
             AND translation.locale = :locale
            WHERE item.festival_revision_id = :festivalRevisionId
            ORDER BY item.sort_order, item.id
            """, parameters(festivalRevisionId).addValue("locale", locale),
            (resultSet, rowNumber) -> {
                requireTranslation(resultSet, "Published prohibited item is missing its requested translation.");
                return resultSet.getString("label");
            }
        );
        List<String> messages = jdbc.query("""
            SELECT message
            FROM prohibited_messages
            WHERE festival_revision_id = :festivalRevisionId
              AND locale = :locale
            """, parameters(festivalRevisionId).addValue("locale", locale),
            (resultSet, rowNumber) -> resultSet.getString("message")
        );
        if (messages.size() > 1) {
            throw new CatalogIntegrityException("Published prohibited content has duplicate locale messages.");
        }
        return new ProhibitedItems(items, messages.isEmpty() ? null : messages.getFirst());
    }

    @Transactional(readOnly = true)
    public Optional<Artist> findArtist(UUID festivalRevisionId, String artistId, String locale) {
        List<ArtistHeader> headers = jdbc.query("""
            SELECT a.id, a.category, a.image_url, a.image_width, a.image_height,
                   translation.locale AS translation_locale,
                   translation.name, translation.image_alt, translation.introduction
            FROM artists a
            LEFT JOIN artist_translations translation
              ON translation.festival_revision_id = a.festival_revision_id
             AND translation.artist_id = a.id
             AND translation.locale = :locale
            WHERE a.festival_revision_id = :festivalRevisionId
              AND a.id = :artistId
            """, parameters(festivalRevisionId)
                .addValue("artistId", artistId)
                .addValue("locale", locale),
            (resultSet, rowNumber) -> mapArtistHeader(resultSet)
        );
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        ArtistHeader header = headers.getFirst();
        return Optional.of(new Artist(
            header.id(),
            header.category(),
            header.name(),
            header.image(),
            header.introduction(),
            loadLinks(festivalRevisionId, artistId, locale),
            loadSongs(festivalRevisionId, artistId, locale),
            loadPerformances(festivalRevisionId, artistId)
        ));
    }

    private List<Link> loadLinks(UUID festivalRevisionId, String artistId, String locale) {
        return jdbc.query("""
            SELECT link.url, translation.locale AS translation_locale, translation.label
            FROM artist_links link
            LEFT JOIN artist_link_translations translation
              ON translation.festival_revision_id = link.festival_revision_id
             AND translation.artist_id = link.artist_id
             AND translation.sort_order = link.sort_order
             AND translation.locale = :locale
            WHERE link.festival_revision_id = :festivalRevisionId
              AND link.artist_id = :artistId
            ORDER BY link.sort_order
            """, parameters(festivalRevisionId)
                .addValue("artistId", artistId)
                .addValue("locale", locale),
            (resultSet, rowNumber) -> {
                requireTranslation(resultSet, "Published artist link is missing its requested translation.");
                return new Link(resultSet.getString("label"), resultSet.getString("url"));
            }
        );
    }

    private List<Link> loadSongs(UUID festivalRevisionId, String artistId, String locale) {
        return jdbc.query("""
            SELECT song.url, translation.locale AS translation_locale, translation.title
            FROM artist_songs song
            LEFT JOIN artist_song_translations translation
              ON translation.festival_revision_id = song.festival_revision_id
             AND translation.artist_id = song.artist_id
             AND translation.sort_order = song.sort_order
             AND translation.locale = :locale
            WHERE song.festival_revision_id = :festivalRevisionId
              AND song.artist_id = :artistId
            ORDER BY song.sort_order
            LIMIT 3
            """, parameters(festivalRevisionId)
                .addValue("artistId", artistId)
                .addValue("locale", locale),
            (resultSet, rowNumber) -> {
                requireTranslation(resultSet, "Published artist song is missing its requested translation.");
                return new Link(resultSet.getString("title"), resultSet.getString("url"));
            }
        );
    }

    private List<ArtistPerformance> loadPerformances(UUID festivalRevisionId, String artistId) {
        return jdbc.query("""
            SELECT performance.id, performance.festival_date,
                   performance.starts_at, performance.ends_at
            FROM performance_artists relation
            JOIN performances performance
              ON performance.festival_revision_id = relation.festival_revision_id
             AND performance.id = relation.performance_id
            WHERE relation.festival_revision_id = :festivalRevisionId
              AND relation.artist_id = :artistId
            ORDER BY performance.festival_date, performance.starts_at, performance.id
            """, parameters(festivalRevisionId).addValue("artistId", artistId),
            (resultSet, rowNumber) -> new ArtistPerformance(
                resultSet.getString("id"),
                resultSet.getObject("festival_date", LocalDate.class),
                resultSet.getObject("starts_at", OffsetDateTime.class),
                resultSet.getObject("ends_at", OffsetDateTime.class)
            )
        );
    }

    private Map<String, List<PerformanceArtist>> loadAllPerformanceArtists(
        UUID festivalRevisionId,
        String locale
    ) {
        List<PerformanceArtistRow> rows = queryPerformanceArtists("""
            SELECT relation.performance_id, artist.id AS artist_id,
                   translation.locale AS translation_locale, translation.name
            FROM performance_artists relation
            JOIN artists artist
              ON artist.festival_revision_id = relation.festival_revision_id
             AND artist.id = relation.artist_id
            LEFT JOIN artist_translations translation
              ON translation.festival_revision_id = artist.festival_revision_id
             AND translation.artist_id = artist.id
             AND translation.locale = :locale
            WHERE relation.festival_revision_id = :festivalRevisionId
            ORDER BY relation.performance_id, relation.display_order, artist.id
            """, parameters(festivalRevisionId).addValue("locale", locale));
        Map<String, List<PerformanceArtist>> byPerformance = new LinkedHashMap<>();
        for (PerformanceArtistRow row : rows) {
            byPerformance.computeIfAbsent(row.performanceId(), ignored -> new ArrayList<>())
                .add(row.artist());
        }
        return byPerformance;
    }

    private List<PerformanceArtist> loadPerformanceArtists(
        UUID festivalRevisionId,
        String performanceId,
        String locale
    ) {
        return queryPerformanceArtists("""
            SELECT relation.performance_id, artist.id AS artist_id,
                   translation.locale AS translation_locale, translation.name
            FROM performance_artists relation
            JOIN artists artist
              ON artist.festival_revision_id = relation.festival_revision_id
             AND artist.id = relation.artist_id
            LEFT JOIN artist_translations translation
              ON translation.festival_revision_id = artist.festival_revision_id
             AND translation.artist_id = artist.id
             AND translation.locale = :locale
            WHERE relation.festival_revision_id = :festivalRevisionId
              AND relation.performance_id = :performanceId
            ORDER BY relation.display_order, artist.id
            """, parameters(festivalRevisionId)
                .addValue("performanceId", performanceId)
                .addValue("locale", locale)).stream()
            .map(PerformanceArtistRow::artist)
            .toList();
    }

    private List<PerformanceArtistRow> queryPerformanceArtists(
        String sql,
        MapSqlParameterSource parameters
    ) {
        return jdbc.query(sql, parameters, (resultSet, rowNumber) -> {
            requireTranslation(resultSet, "Published performance artist is missing its requested translation.");
            return new PerformanceArtistRow(
                resultSet.getString("performance_id"),
                new PerformanceArtist(resultSet.getString("artist_id"), resultSet.getString("name"))
            );
        });
    }

    private PerformanceHeader mapPerformanceHeader(ResultSet resultSet) throws SQLException {
        requireTranslation(resultSet, "Published performance is missing its requested translation.");
        return new PerformanceHeader(
            resultSet.getString("id"),
            resultSet.getObject("festival_date", LocalDate.class),
            resultSet.getString("title"),
            resultSet.getObject("starts_at", OffsetDateTime.class),
            resultSet.getObject("ends_at", OffsetDateTime.class),
            resultSet.getString("description")
        );
    }

    private PerformanceItem performance(PerformanceHeader header, List<PerformanceArtist> artists) {
        return new PerformanceItem(
            header.id(),
            header.date(),
            header.title(),
            artists,
            header.startsAt(),
            header.endsAt(),
            header.description()
        );
    }

    private LineupItem mapLineupItem(ResultSet resultSet) throws SQLException {
        requireTranslation(resultSet, "Published lineup artist is missing its requested translation.");
        return new LineupItem(
            resultSet.getString("artist_id"),
            resultSet.getString("performance_id"),
            resultSet.getString("name"),
            new Image(
                resultSet.getString("image_url"),
                resultSet.getString("image_alt"),
                resultSet.getInt("image_width"),
                resultSet.getInt("image_height")
            )
        );
    }

    private ArtistHeader mapArtistHeader(ResultSet resultSet) throws SQLException {
        requireTranslation(resultSet, "Published artist is missing its requested translation.");
        return new ArtistHeader(
            resultSet.getString("id"),
            resultSet.getString("category"),
            resultSet.getString("name"),
            new Image(
                resultSet.getString("image_url"),
                resultSet.getString("image_alt"),
                resultSet.getInt("image_width"),
                resultSet.getInt("image_height")
            ),
            resultSet.getString("introduction")
        );
    }

    private void requireTranslation(ResultSet resultSet, String message) throws SQLException {
        if (resultSet.getString("translation_locale") == null) {
            throw new CatalogIntegrityException(message);
        }
    }

    private MapSqlParameterSource parameters(UUID festivalRevisionId) {
        return new MapSqlParameterSource("festivalRevisionId", festivalRevisionId);
    }

    public record Image(String url, String alt, int width, int height) {}

    public record LineupItem(String artistId, String performanceId, String name, Image image) {}

    public record Link(String label, String url) {}

    public record ArtistPerformance(String id, LocalDate date, OffsetDateTime startsAt, OffsetDateTime endsAt) {}

    public record TimetableAxis(LocalTime startTime, LocalTime endTime) {}

    public record PerformanceArtist(String id, String name) {}

    public record PerformanceItem(
        String id,
        LocalDate date,
        String title,
        List<PerformanceArtist> artists,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String description
    ) {
        public PerformanceItem {
            artists = List.copyOf(artists);
        }
    }

    public record Timetable(List<LocalDate> dates, TimetableAxis axis, List<PerformanceItem> items) {
        public Timetable {
            dates = List.copyOf(dates);
            items = List.copyOf(items);
        }
    }

    public record ProhibitedItems(List<String> items, String message) {
        public ProhibitedItems {
            items = List.copyOf(items);
        }
    }

    public record Artist(
        String id,
        String category,
        String name,
        Image image,
        String introduction,
        List<Link> socialLinks,
        List<Link> songs,
        List<ArtistPerformance> performances
    ) {
        public Artist {
            socialLinks = List.copyOf(socialLinks);
            songs = List.copyOf(songs);
            performances = List.copyOf(performances);
        }
    }

    private record ArtistHeader(
        String id,
        String category,
        String name,
        Image image,
        String introduction
    ) {}

    private record PerformanceHeader(
        String id,
        LocalDate date,
        String title,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String description
    ) {}

    private record PerformanceArtistRow(String performanceId, PerformanceArtist artist) {}
}

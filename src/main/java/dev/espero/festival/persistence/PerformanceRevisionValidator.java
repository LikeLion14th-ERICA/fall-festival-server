package dev.espero.festival.persistence;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Validates the internal V13 performance catalog before a revision is published. */
@Repository
@Profile("db")
public final class PerformanceRevisionValidator {

    private static final Set<String> LOCALES = Set.of("ko", "en", "zh-Hans", "ja");
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final NamedParameterJdbcTemplate jdbc;

    public PerformanceRevisionValidator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(UUID revisionId) {
        require(revisionId != null, "Revision id is required for performance validation.");
        MapSqlParameterSource parameters = new MapSqlParameterSource("revisionId", revisionId);

        jdbc.query("""
            SELECT image_url, image_width, image_height
            FROM artists
            WHERE festival_revision_id = :revisionId
            """, parameters, resultSet -> {
                image(
                    resultSet.getString("image_url"),
                    resultSet.getInt("image_width"),
                    resultSet.getInt("image_height")
                );
            });

        jdbc.query("""
            SELECT url FROM artist_links WHERE festival_revision_id = :revisionId
            UNION ALL
            SELECT url FROM artist_songs WHERE festival_revision_id = :revisionId
            """, parameters, (RowCallbackHandler) resultSet -> https(resultSet.getString("url")));

        jdbc.query("""
            SELECT p.festival_date, p.starts_at, p.ends_at, d.festival_date AS matching_day
            FROM performances p
            LEFT JOIN festival_days d
              ON d.festival_revision_id = p.festival_revision_id
             AND d.festival_date = p.festival_date
            WHERE p.festival_revision_id = :revisionId
            """, parameters, resultSet -> {
                java.time.LocalDate festivalDate = resultSet.getObject(
                    "festival_date", java.time.LocalDate.class
                );
                OffsetDateTime startsAt = resultSet.getObject("starts_at", OffsetDateTime.class);
                OffsetDateTime endsAt = resultSet.getObject("ends_at", OffsetDateTime.class);
                require(resultSet.getObject("matching_day") != null,
                    "Performance references a festival day outside its revision.");
                require(startsAt != null && endsAt != null && startsAt.isBefore(endsAt),
                    "Performance start must be before its end.");
                require(startsAt.atZoneSameInstant(KOREA).toLocalDate().equals(festivalDate),
                    "Performance start does not fall on festivalDate in Asia/Seoul.");
            });

        jdbc.query("""
            SELECT axis_start_time, axis_end_time
            FROM timetable_configs
            WHERE festival_revision_id = :revisionId
            """, parameters, resultSet -> {
                java.time.LocalTime start = resultSet.getObject(
                    "axis_start_time", java.time.LocalTime.class
                );
                java.time.LocalTime end = resultSet.getObject(
                    "axis_end_time", java.time.LocalTime.class
                );
                require(start != null && end != null && start.isBefore(end),
                    "Timetable axis start must be before its end.");
            });

        jdbc.query("""
            SELECT locale FROM artist_translations WHERE festival_revision_id = :revisionId
            UNION
            SELECT locale FROM artist_link_translations WHERE festival_revision_id = :revisionId
            UNION
            SELECT locale FROM artist_song_translations WHERE festival_revision_id = :revisionId
            UNION
            SELECT locale FROM performance_translations WHERE festival_revision_id = :revisionId
            UNION
            SELECT locale FROM prohibited_item_translations WHERE festival_revision_id = :revisionId
            UNION
            SELECT locale FROM prohibited_messages WHERE festival_revision_id = :revisionId
            """, parameters, (RowCallbackHandler) resultSet -> require(
                LOCALES.contains(resultSet.getString("locale")),
                "Performance catalog contains an unsupported locale."
            ));

        requireNoMissingKorean("artists", "artist_translations", "id", "artist_id", parameters);
        requireNoMissingKorean(
            "performances", "performance_translations", "id", "performance_id", parameters
        );
        requireNoMissingKorean(
            "prohibited_items", "prohibited_item_translations", "id", "item_id", parameters
        );
        requireNoMissingKoreanComposite("artist_links", "artist_link_translations", parameters);
        requireNoMissingKoreanComposite("artist_songs", "artist_song_translations", parameters);

        Long messageCount = count("""
            SELECT COUNT(*) FROM prohibited_messages
            WHERE festival_revision_id = :revisionId
            """, parameters);
        Long koreanMessageCount = count("""
            SELECT COUNT(*) FROM prohibited_messages
            WHERE festival_revision_id = :revisionId AND locale = 'ko'
            """, parameters);
        require(messageCount == 0L || koreanMessageCount > 0L,
            "Non-empty prohibited messages need a Korean row.");
    }

    private void requireNoMissingKorean(
        String parentTable,
        String translationTable,
        String parentId,
        String translationParentId,
        MapSqlParameterSource parameters
    ) {
        Long missing = count("""
            SELECT COUNT(*)
            FROM %s parent
            WHERE parent.festival_revision_id = :revisionId
              AND NOT EXISTS (
                  SELECT 1 FROM %s translation
                  WHERE translation.festival_revision_id = parent.festival_revision_id
                    AND translation.%s = parent.%s
                    AND translation.locale = 'ko'
              )
            """.formatted(parentTable, translationTable, translationParentId, parentId), parameters);
        require(missing == 0L, parentTable + " contains an entity without a Korean translation.");
    }

    private void requireNoMissingKoreanComposite(
        String parentTable,
        String translationTable,
        MapSqlParameterSource parameters
    ) {
        Long missing = count("""
            SELECT COUNT(*)
            FROM %s parent
            WHERE parent.festival_revision_id = :revisionId
              AND NOT EXISTS (
                  SELECT 1 FROM %s translation
                  WHERE translation.festival_revision_id = parent.festival_revision_id
                    AND translation.artist_id = parent.artist_id
                    AND translation.sort_order = parent.sort_order
                    AND translation.locale = 'ko'
              )
            """.formatted(parentTable, translationTable), parameters);
        require(missing == 0L, parentTable + " contains an entity without a Korean translation.");
    }

    private Long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0L : value;
    }

    private void image(String value, int width, int height) {
        uri(value, "Artist image URL");
        require(width > 0 && height > 0, "Artist image dimensions must be positive.");
    }

    private void https(String value) {
        uri(value, "Artist link/song URL");
        require(value.startsWith("https://"), "Artist link/song URL must use https://.");
    }

    private void uri(String value, String field) {
        require(value != null && !value.isBlank(), field + " must not be blank.");
        require(value.codePoints().allMatch(character -> character <= 0x7f),
            field + " must contain ASCII URI characters.");
        try {
            new URI(value);
        } catch (URISyntaxException exception) {
            throw new CatalogIntegrityException(field + " must be a valid URI reference.");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CatalogIntegrityException(message);
        }
    }
}

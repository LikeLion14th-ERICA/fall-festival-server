package dev.espero.festival.persistence;

import dev.espero.festival.domain.StampGuide;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Only active with SPRING_PROFILES_ACTIVE=db; the default profile excludes
 * DataSourceAutoConfiguration, so no NamedParameterJdbcTemplate bean would
 * exist to inject otherwise.
 */
@Repository
@Profile("db")
public class StampGuideStore {

    private final NamedParameterJdbcTemplate jdbc;

    public StampGuideStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Loads the immutable guide row belonging to one revision. */
    public Optional<StampGuide> find(UUID festivalRevisionId) {
        return find(festivalRevisionId, "ko");
    }

    /**
     * Korean text lives in the guide row; other locales come from
     * stamp_guide_translations while dates and the QR value stay shared. A
     * locale without a translation row finds no guide rather than the Korean
     * one.
     */
    public Optional<StampGuide> find(UUID festivalRevisionId, String locale) {
        return jdbc.query("""
            SELECT CASE WHEN :locale = 'ko' THEN current.title ELSE translation.title END AS title,
                   current.dates,
                   CASE WHEN :locale = 'ko' THEN current.instructions ELSE translation.instructions END
                       AS instructions,
                   CASE WHEN :locale = 'ko' THEN current.reward_name ELSE translation.reward_name END
                       AS reward_name,
                   CASE WHEN :locale = 'ko' THEN current.reward_location_text
                       ELSE translation.reward_location_text END AS reward_location_text,
                   CASE WHEN :locale = 'ko' THEN current.reward_hours_text
                       ELSE translation.reward_hours_text END AS reward_hours_text,
                   CASE WHEN :locale = 'ko' THEN current.reward_notice ELSE translation.reward_notice END
                       AS reward_notice,
                   current.qr_value, current.updated_at
            FROM stamp_guide_revisions current
            LEFT JOIN stamp_guide_translations translation
              ON translation.festival_revision_id = current.festival_revision_id
             AND translation.id = current.id
             AND translation.locale = :locale
            WHERE current.id = 1 AND current.festival_revision_id = :festivalRevisionId
              AND (:locale = 'ko' OR translation.locale IS NOT NULL)
            """, new MapSqlParameterSource("festivalRevisionId", festivalRevisionId).addValue("locale", locale),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private StampGuide map(ResultSet resultSet) throws SQLException {
        return new StampGuide(
            resultSet.getString("title"),
            readDates(resultSet),
            readStrings(resultSet, "instructions"),
            resultSet.getString("reward_name"),
            resultSet.getString("reward_location_text"),
            resultSet.getString("reward_hours_text"),
            resultSet.getString("reward_notice"),
            resultSet.getString("qr_value"),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private List<LocalDate> readDates(ResultSet resultSet) throws SQLException {
        java.sql.Array array = resultSet.getArray("dates");
        if (array == null) {
            return List.of();
        }
        Date[] values = (Date[]) array.getArray();
        return Arrays.stream(values).map(Date::toLocalDate).toList();
    }

    private List<String> readStrings(ResultSet resultSet, String column) throws SQLException {
        java.sql.Array array = resultSet.getArray(column);
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }
}

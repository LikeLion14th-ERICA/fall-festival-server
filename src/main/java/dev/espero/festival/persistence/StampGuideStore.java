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
        return jdbc.query("""
            SELECT current.title, current.dates, current.instructions,
                   current.reward_name, current.reward_location_text,
                   current.reward_hours_text, current.reward_notice,
                   current.qr_value, current.updated_at
            FROM stamp_guide_revisions current
            WHERE current.id = 1 AND current.festival_revision_id = :festivalRevisionId
            """, new MapSqlParameterSource("festivalRevisionId", festivalRevisionId),
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

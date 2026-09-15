package dev.espero.festival.persistence;

import dev.espero.festival.domain.StampGuide;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
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

    public Optional<StampGuide> find() {
        return jdbc.query("""
            SELECT title, dates, instructions, reward_name, reward_location_text,
                   reward_hours_text, reward_notice, qr_value, updated_at
            FROM stamp_guide
            WHERE id = 1
            """, Map.of(), (resultSet, rowNumber) -> map(resultSet)
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

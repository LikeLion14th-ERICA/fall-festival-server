package dev.espero.festival.persistence;

import dev.espero.festival.domain.CrowdingRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Only active with SPRING_PROFILES_ACTIVE=db; the default profile excludes
 * DataSourceAutoConfiguration, so no NamedParameterJdbcTemplate bean would
 * exist to inject otherwise.
 *
 * Read-only: no admin write endpoint exists yet, so rows are only ever
 * inserted manually until A's authentication/authorization foundation lands
 * (see V5__create_crowding_state.sql).
 */
@Repository
@Profile("db")
public class CrowdingStore {

    private final NamedParameterJdbcTemplate jdbc;

    public CrowdingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CrowdingRecord> findFor(LocalDate operatingDay) {
        return jdbc.query("""
            SELECT level, updated_at
            FROM crowding_state
            WHERE operating_day = :operatingDay
            """,
            new MapSqlParameterSource("operatingDay", operatingDay),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private CrowdingRecord map(ResultSet resultSet) throws SQLException {
        return new CrowdingRecord(
            resultSet.getString("level"),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }
}

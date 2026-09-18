package dev.espero.festival.persistence;

import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.domain.CrowdingSchedule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL access for revision-independent crowding state and schedules. */
@Repository
@Profile("db")
public class CrowdingStore {

    private final NamedParameterJdbcTemplate jdbc;

    public CrowdingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads the authoritative state keyed by festival and operating date. */
    public Optional<CrowdingRecord> findFor(UUID festivalId, LocalDate operatingDate) {
        return jdbc.query("""
            SELECT level, updated_at
            FROM crowding_state_dynamic
            WHERE festival_id = :festivalId
              AND operating_date = :operatingDate
            """,
            stateParameters(festivalId, operatingDate),
            (resultSet, rowNumber) -> mapRecord(resultSet)
        ).stream().findFirst();
    }

    /** Locks a state row before an administrator checks its ETag and writes it. */
    public Optional<CrowdingRecord> findForUpdate(UUID festivalId, LocalDate operatingDate) {
        Optional<CrowdingRecord> current = jdbc.query("""
            SELECT level, updated_at
            FROM crowding_state_dynamic
            WHERE festival_id = :festivalId
              AND operating_date = :operatingDate
            FOR UPDATE
            """,
            stateParameters(festivalId, operatingDate),
            (resultSet, rowNumber) -> mapRecord(resultSet)
        ).stream().findFirst();
        if (current.isPresent()) {
            return current;
        }
        // A missing state row has nothing to lock. Serialize the first insert
        // on the festival row so two concurrent saves cannot race into the
        // composite primary key.
        jdbc.query("""
            SELECT id
            FROM festivals
            WHERE id = :festivalId
            FOR UPDATE
            """, new MapSqlParameterSource("festivalId", festivalId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
        return jdbc.query("""
            SELECT level, updated_at
            FROM crowding_state_dynamic
            WHERE festival_id = :festivalId
              AND operating_date = :operatingDate
            FOR UPDATE
            """,
            stateParameters(festivalId, operatingDate),
            (resultSet, rowNumber) -> mapRecord(resultSet)
        ).stream().findFirst();
    }

    /** Reads all dates from the published FestivalRevision in date order. */
    public List<CrowdingSchedule> findSchedules(UUID festivalRevisionId) {
        return jdbc.query("""
            SELECT festival_date, opens_at, closes_at
            FROM festival_days
            WHERE festival_revision_id = :festivalRevisionId
            ORDER BY festival_date
            """,
            new MapSqlParameterSource("festivalRevisionId", festivalRevisionId),
            (resultSet, rowNumber) -> mapSchedule(resultSet)
        );
    }

    /** Inserts or updates the authoritative state. Same-level saves are no-ops. */
    @Transactional
    public CrowdingMutation save(UUID festivalId, LocalDate operatingDate, String level, Instant updatedAt) {
        Optional<CrowdingRecord> current = findForUpdate(festivalId, operatingDate);
        if (current.isPresent() && current.get().level().equals(level)) {
            return new CrowdingMutation(current.get(), false);
        }

        if (current.isPresent()) {
            jdbc.update("""
                UPDATE crowding_state_dynamic
                SET level = :level,
                    updated_at = :updatedAt
                WHERE festival_id = :festivalId
                  AND operating_date = :operatingDate
                """, stateParameters(festivalId, operatingDate)
                .addValue("level", level)
                .addValue("updatedAt", atUtc(updatedAt)));
        } else {
            jdbc.update("""
                INSERT INTO crowding_state_dynamic (
                    festival_id, operating_date, level, updated_at
                ) VALUES (:festivalId, :operatingDate, :level, :updatedAt)
                """, stateParameters(festivalId, operatingDate)
                .addValue("level", level)
                .addValue("updatedAt", atUtc(updatedAt)));
        }
        return new CrowdingMutation(new CrowdingRecord(level, updatedAt), true);
    }

    private CrowdingRecord mapRecord(ResultSet resultSet) throws SQLException {
        return new CrowdingRecord(
            resultSet.getString("level"),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private CrowdingSchedule mapSchedule(ResultSet resultSet) throws SQLException {
        return new CrowdingSchedule(
            resultSet.getObject("festival_date", LocalDate.class),
            resultSet.getObject("opens_at", OffsetDateTime.class),
            resultSet.getObject("closes_at", OffsetDateTime.class)
        );
    }

    private MapSqlParameterSource stateParameters(UUID festivalId, LocalDate operatingDate) {
        return new MapSqlParameterSource()
            .addValue("festivalId", festivalId)
            .addValue("operatingDate", operatingDate);
    }

    private OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record CrowdingMutation(CrowdingRecord record, boolean changed) {}
}

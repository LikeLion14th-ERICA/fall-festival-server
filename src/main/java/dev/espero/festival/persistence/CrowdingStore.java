package dev.espero.festival.persistence;

import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.domain.CrowdingOperatingHours;
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

    /** All dynamic writes and catalog publication acquire the festival first. */
    public void lockFestival(UUID festivalId) {
        jdbc.query("""
            SELECT id FROM festivals WHERE id = :festivalId FOR UPDATE
            """, new MapSqlParameterSource("festivalId", festivalId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    }

    /** Locks the festival before the state row, including for existing rows. */
    public Optional<CrowdingRecord> findForUpdate(UUID festivalId, LocalDate operatingDate) {
        lockFestival(festivalId);
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
        return findOperatingHours(festivalRevisionId).stream()
            .map(hours -> new CrowdingSchedule(hours.operatingDate(), hours.opensAt(), hours.closesAt()))
            .toList();
    }

    /** Overlay by row presence, so even invalid/null catalog times remain editable. */
    public List<CrowdingOperatingHours> findOperatingHours(UUID festivalRevisionId) {
        return jdbc.query("""
            SELECT day.festival_date,
                   CASE WHEN hours.festival_id IS NULL THEN day.opens_at ELSE hours.opens_at END AS opens_at,
                   CASE WHEN hours.festival_id IS NULL THEN day.closes_at ELSE hours.closes_at END AS closes_at,
                   hours.updated_at
            FROM festival_days day
            JOIN festival_revisions revision ON revision.id = day.festival_revision_id
            LEFT JOIN crowding_operating_hours hours
              ON hours.festival_id = revision.festival_id AND hours.operating_date = day.festival_date
            WHERE day.festival_revision_id = :festivalRevisionId
            ORDER BY day.festival_date
            """,
            new MapSqlParameterSource("festivalRevisionId", festivalRevisionId),
            (resultSet, rowNumber) -> new CrowdingOperatingHours(
                resultSet.getObject("festival_date", LocalDate.class),
                resultSet.getObject("opens_at", OffsetDateTime.class),
                resultSet.getObject("closes_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class) == null ? null
                    : resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
            )
        );
    }

    /** First confirmation persists even if the catalog fallback has the same times. */
    @Transactional
    public boolean saveOperatingHours(UUID festivalId, LocalDate date, OffsetDateTime opensAt,
        OffsetDateTime closesAt, Instant updatedAt) {
        lockFestival(festivalId);
        List<CrowdingOperatingHours> saved = jdbc.query("""
            SELECT operating_date, opens_at, closes_at, updated_at FROM crowding_operating_hours
            WHERE festival_id = :festivalId AND operating_date = :operatingDate FOR UPDATE
            """, stateParameters(festivalId, date), (row, number) -> new CrowdingOperatingHours(
                row.getObject("operating_date", LocalDate.class), row.getObject("opens_at", OffsetDateTime.class),
                row.getObject("closes_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class).toInstant()
            ));
        if (!saved.isEmpty() && saved.getFirst().opensAt().isEqual(opensAt)
            && saved.getFirst().closesAt().isEqual(closesAt)) {
            return false;
        }
        jdbc.update("""
            INSERT INTO crowding_operating_hours (festival_id, operating_date, opens_at, closes_at, updated_at)
            VALUES (:festivalId, :operatingDate, :opensAt, :closesAt, :updatedAt)
            ON CONFLICT (festival_id, operating_date) DO UPDATE
            SET opens_at = EXCLUDED.opens_at, closes_at = EXCLUDED.closes_at, updated_at = EXCLUDED.updated_at
            """, stateParameters(festivalId, date).addValue("opensAt", opensAt)
                .addValue("closesAt", closesAt).addValue("updatedAt", atUtc(updatedAt)));
        return true;
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

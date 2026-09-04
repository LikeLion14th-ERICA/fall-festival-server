package dev.espero.stamptest.persistence;

import static dev.espero.stamptest.support.DbTime.instant;
import static dev.espero.stamptest.support.DbTime.value;

import dev.espero.stamptest.domain.DomainModels.ClockStatus;
import dev.espero.stamptest.domain.DomainModels.ClockTestRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ClockTestStore {

    private static final String COLUMNS = """
        id, participant_id, status, started_at, ends_at, next_scheduled_at,
        next_sequence, sent_count, failed_count, updated_at
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ClockTestStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ClockTestRun start(
        UUID participantId,
        Instant startedAt,
        Instant endsAt,
        Instant firstScheduledAt
    ) {
        // The participant row is the stable per-owner mutex. It serializes concurrent
        // starts across application replicas and makes PUT /clock-test idempotent.
        jdbc.queryForObject(
            "SELECT id FROM participants WHERE id = :participantId FOR UPDATE",
            Map.of("participantId", participantId),
            UUID.class
        );
        Optional<ClockTestRun> active = findActive(participantId);
        if (active.isPresent()) {
            return active.get();
        }
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("participantId", participantId)
            .addValue("startedAt", value(startedAt))
            .addValue("endsAt", value(endsAt))
            .addValue("nextScheduledAt", value(firstScheduledAt));
        jdbc.update("""
            INSERT INTO clock_test_runs(
                id, participant_id, status, started_at, ends_at, next_scheduled_at,
                next_sequence, sent_count, failed_count, updated_at
            ) VALUES (
                :id, :participantId, 'ACTIVE', :startedAt, :endsAt, :nextScheduledAt,
                1, 0, 0, :startedAt
            )
            """, parameters);
        return findById(id).orElseThrow();
    }

    public Optional<ClockTestRun> findActive(UUID participantId) {
        return jdbc.query("""
            SELECT %s FROM clock_test_runs
            WHERE participant_id = :participantId AND status = 'ACTIVE'
            ORDER BY started_at DESC
            LIMIT 1
            """.formatted(COLUMNS), Map.of("participantId", participantId),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    public Optional<ClockTestRun> findLatest(UUID participantId) {
        return jdbc.query("""
            SELECT %s FROM clock_test_runs
            WHERE participant_id = :participantId
            ORDER BY started_at DESC
            LIMIT 1
            """.formatted(COLUMNS), Map.of("participantId", participantId),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    public Optional<ClockTestRun> findById(UUID id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM clock_test_runs WHERE id = :id",
            Map.of("id", id),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    public Optional<ClockTestRun> findByIdForUpdate(UUID id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM clock_test_runs WHERE id = :id FOR UPDATE",
            Map.of("id", id),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    public List<UUID> findDueRunIds(Instant now, int limit) {
        return jdbc.query("""
            SELECT id FROM clock_test_runs
            WHERE status = 'ACTIVE' AND next_scheduled_at <= :now
            ORDER BY next_scheduled_at
            LIMIT :limit
            """, new MapSqlParameterSource().addValue("now", value(now)).addValue("limit", limit),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    }

    public ClockTestRun advance(
        UUID id,
        int nextSequence,
        Instant nextScheduledAt,
        ClockStatus status,
        Instant now
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("nextSequence", nextSequence)
            .addValue("nextScheduledAt", value(nextScheduledAt))
            .addValue("status", status.name())
            .addValue("now", value(now));
        jdbc.update("""
            UPDATE clock_test_runs
            SET next_sequence = :nextSequence, next_scheduled_at = :nextScheduledAt,
                status = :status, updated_at = :now
            WHERE id = :id
            """, parameters);
        return findById(id).orElseThrow();
    }

    public Optional<ClockTestRun> stop(UUID participantId, Instant now) {
        Optional<ClockTestRun> latest = findLatest(participantId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        ClockTestRun run = latest.get();
        if (run.status() == ClockStatus.ACTIVE) {
            jdbc.update("""
                UPDATE clock_test_runs
                SET status = 'STOPPED', next_scheduled_at = NULL, updated_at = :now
                WHERE id = :id
                """, Map.of("now", value(now), "id", run.id()));
        }
        return findById(run.id());
    }

    public void incrementTerminalCount(UUID runId, boolean accepted, Instant now) {
        String column = accepted ? "sent_count" : "failed_count";
        jdbc.update("""
            UPDATE clock_test_runs
            SET %s = %s + 1, updated_at = :now
            WHERE id = :id
            """.formatted(column, column), Map.of("now", value(now), "id", runId));
    }

    private ClockTestRun map(ResultSet resultSet) throws SQLException {
        return new ClockTestRun(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("participant_id", UUID.class),
            ClockStatus.valueOf(resultSet.getString("status")),
            instant(resultSet, "started_at"),
            instant(resultSet, "ends_at"),
            instant(resultSet, "next_scheduled_at"),
            resultSet.getInt("next_sequence"),
            resultSet.getInt("sent_count"),
            resultSet.getInt("failed_count"),
            instant(resultSet, "updated_at")
        );
    }
}

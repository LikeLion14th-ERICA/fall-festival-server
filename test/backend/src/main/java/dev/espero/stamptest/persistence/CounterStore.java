package dev.espero.stamptest.persistence;

import static dev.espero.stamptest.support.DbTime.value;

import dev.espero.stamptest.domain.DomainModels.CounterResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CounterStore {

    private final NamedParameterJdbcTemplate jdbc;

    public CounterStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CounterResult get(UUID participantId) {
        return jdbc.queryForObject(
            "SELECT value, version FROM counters WHERE participant_id = :participantId",
            Map.of("participantId", participantId),
            (resultSet, rowNumber) -> new CounterResult(
                resultSet.getInt("value"),
                resultSet.getLong("version"),
                false
            )
        );
    }

    @Transactional
    public CounterResult adjust(UUID participantId, UUID operationId, int delta, Instant now) {
        Optional<CounterResult> existing = findOperation(participantId, operationId);
        if (existing.isPresent()) {
            CounterResult result = existing.get();
            return new CounterResult(result.value(), result.version(), true);
        }

        CounterResult current = jdbc.queryForObject(
            "SELECT value, version FROM counters WHERE participant_id = :participantId FOR UPDATE",
            Map.of("participantId", participantId),
            (resultSet, rowNumber) -> new CounterResult(
                resultSet.getInt("value"),
                resultSet.getLong("version"),
                false
            )
        );

        existing = findOperation(participantId, operationId);
        if (existing.isPresent()) {
            CounterResult result = existing.get();
            return new CounterResult(result.value(), result.version(), true);
        }

        int adjusted = Math.max(0, Math.min(10, current.value() + delta));
        long version = adjusted == current.value() ? current.version() : current.version() + 1;
        if (adjusted != current.value()) {
            jdbc.update("""
                UPDATE counters
                SET value = :value, version = :version, updated_at = :updatedAt
                WHERE participant_id = :participantId
                """, Map.of(
                    "value", adjusted,
                    "version", version,
                    "updatedAt", value(now),
                    "participantId", participantId
                ));
        }
        jdbc.update("""
            INSERT INTO counter_operations(
                id, participant_id, operation_id, delta, result_value, result_version, created_at
            ) VALUES (
                :id, :participantId, :operationId, :delta, :resultValue, :resultVersion, :createdAt
            )
            """, Map.of(
                "id", UUID.randomUUID(),
                "participantId", participantId,
                "operationId", operationId,
                "delta", delta,
                "resultValue", adjusted,
                "resultVersion", version,
                "createdAt", value(now)
            ));
        return new CounterResult(adjusted, version, false);
    }

    private Optional<CounterResult> findOperation(UUID participantId, UUID operationId) {
        return jdbc.query("""
            SELECT result_value, result_version
            FROM counter_operations
            WHERE participant_id = :participantId AND operation_id = :operationId
            """, Map.of("participantId", participantId, "operationId", operationId),
            (resultSet, rowNumber) -> new CounterResult(
                resultSet.getInt("result_value"),
                resultSet.getLong("result_version"),
                true
            )
        ).stream().findFirst();
    }
}

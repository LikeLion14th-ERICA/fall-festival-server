package dev.espero.festival.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for hashed administrator idempotency records. */
@Repository
@Profile("db")
public class AdminIdempotencyStore {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminIdempotencyStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Reservation reserve(IdempotencyRequest request, UUID leaseToken, Instant now, Duration leaseDuration) {
        Instant expiresAt = now.plus(leaseDuration);
        int inserted = jdbc.update("""
            INSERT INTO admin_idempotency_records (
                id, scope_hash, key_hash, request_fingerprint, state, lease_token, lease_expires_at,
                response_status, response_content_type, response_body, created_at, updated_at, completed_at
            ) VALUES (
                :id, :scopeHash, :keyHash, :fingerprint, 'IN_PROGRESS', :leaseToken, :leaseExpiresAt,
                NULL, NULL, NULL, :now, :now, NULL
            ) ON CONFLICT (scope_hash, key_hash) DO NOTHING
            """, parameters(request, UUID.randomUUID(), leaseToken, expiresAt, now));
        if (inserted == 1) {
            return new Reservation.Acquired(leaseToken);
        }

        StoredRecord current;
        try {
            current = findForUpdateNowait(request).orElseThrow(() ->
                new IllegalStateException("Idempotency record disappeared while being reserved"));
        } catch (DataAccessException exception) {
            if (!isLockUnavailable(exception)) {
                throw exception;
            }
            // The business transaction holds this row until it has either
            // committed a completed result or rolled back. Do not wait and
            // accidentally execute a duplicate after it releases the lock.
            return Reservation.InProgress.INSTANCE;
        }
        if (!current.fingerprint().equals(request.fingerprint())) {
            return Reservation.KeyReused.INSTANCE;
        }
        if (current.isCompleted()) {
            return new Reservation.Replay(current.response());
        }
        if (current.leaseExpiresAt().isAfter(now)) {
            return Reservation.InProgress.INSTANCE;
        }

        int updated = jdbc.update("""
            UPDATE admin_idempotency_records
            SET lease_token = :leaseToken,
                lease_expires_at = :leaseExpiresAt,
                updated_at = :now
            WHERE id = :id
              AND state = 'IN_PROGRESS'
            """, new MapSqlParameterSource()
            .addValue("id", current.id())
            .addValue("leaseToken", leaseToken)
            .addValue("leaseExpiresAt", atUtc(expiresAt))
            .addValue("now", atUtc(now)));
        if (updated != 1) {
            throw new IllegalStateException("Idempotency lease changed while being reserved");
        }
        return new Reservation.Acquired(leaseToken);
    }

    void lockOwned(IdempotencyRequest request, UUID leaseToken) {
        StoredRecord current = findForUpdate(request).orElseThrow(() ->
            new IllegalStateException("Idempotency record disappeared before mutation"));
        if (current.isCompleted() || !leaseToken.equals(current.leaseToken())) {
            throw new IdempotencyOwnershipLostException();
        }
    }

    void complete(IdempotencyRequest request, UUID leaseToken, IdempotencyResponse response, Instant now) {
        int updated = jdbc.update("""
            UPDATE admin_idempotency_records
            SET state = 'COMPLETED',
                lease_token = NULL,
                lease_expires_at = NULL,
                response_status = :responseStatus,
                response_content_type = :responseContentType,
                response_body = :responseBody,
                updated_at = :now,
                completed_at = :now
            WHERE scope_hash = :scopeHash
              AND key_hash = :keyHash
              AND request_fingerprint = :fingerprint
              AND state = 'IN_PROGRESS'
              AND lease_token = :leaseToken
            """, new MapSqlParameterSource()
            .addValue("scopeHash", request.scopeHash())
            .addValue("keyHash", request.keyHash())
            .addValue("fingerprint", request.fingerprint())
            .addValue("leaseToken", leaseToken)
            .addValue("responseStatus", response.status())
            .addValue("responseContentType", response.contentType())
            .addValue("responseBody", response.body())
            .addValue("now", atUtc(now)));
        if (updated != 1) {
            throw new IdempotencyOwnershipLostException();
        }
    }

    Optional<StoredRecord> findForUpdate(IdempotencyRequest request) {
        return jdbc.query("""
            SELECT id, request_fingerprint, state, lease_token, lease_expires_at,
                   response_status, response_content_type, response_body
            FROM admin_idempotency_records
            WHERE scope_hash = :scopeHash
              AND key_hash = :keyHash
            FOR UPDATE
            """, new MapSqlParameterSource()
            .addValue("scopeHash", request.scopeHash())
            .addValue("keyHash", request.keyHash()),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private Optional<StoredRecord> findForUpdateNowait(IdempotencyRequest request) {
        return jdbc.query("""
            SELECT id, request_fingerprint, state, lease_token, lease_expires_at,
                   response_status, response_content_type, response_body
            FROM admin_idempotency_records
            WHERE scope_hash = :scopeHash
              AND key_hash = :keyHash
            FOR UPDATE NOWAIT
            """, new MapSqlParameterSource()
            .addValue("scopeHash", request.scopeHash())
            .addValue("keyHash", request.keyHash()),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    Optional<StoredRecord> find(IdempotencyRequest request) {
        return jdbc.query("""
            SELECT id, request_fingerprint, state, lease_token, lease_expires_at,
                   response_status, response_content_type, response_body
            FROM admin_idempotency_records
            WHERE scope_hash = :scopeHash
              AND key_hash = :keyHash
            """, new MapSqlParameterSource()
            .addValue("scopeHash", request.scopeHash())
            .addValue("keyHash", request.keyHash()),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private MapSqlParameterSource parameters(
        IdempotencyRequest request,
        UUID id,
        UUID leaseToken,
        Instant leaseExpiresAt,
        Instant now
    ) {
        return new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("scopeHash", request.scopeHash())
            .addValue("keyHash", request.keyHash())
            .addValue("fingerprint", request.fingerprint())
            .addValue("leaseToken", leaseToken)
            .addValue("leaseExpiresAt", atUtc(leaseExpiresAt))
            .addValue("now", atUtc(now));
    }

    private StoredRecord map(ResultSet resultSet) throws SQLException {
        Integer status = resultSet.getObject("response_status", Integer.class);
        return new StoredRecord(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("request_fingerprint"),
            resultSet.getString("state"),
            resultSet.getObject("lease_token", UUID.class),
            optionalInstant(resultSet, "lease_expires_at"),
            status == null ? null : new IdempotencyResponse(
                status,
                resultSet.getString("response_content_type"),
                resultSet.getString("response_body")
            )
        );
    }

    private Instant optionalInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private boolean isLockUnavailable(DataAccessException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    sealed interface Reservation permits Reservation.Acquired, Reservation.Replay, Reservation.InProgress, Reservation.KeyReused {
        record Acquired(UUID leaseToken) implements Reservation {}
        record Replay(IdempotencyResponse response) implements Reservation {}
        enum InProgress implements Reservation { INSTANCE }
        enum KeyReused implements Reservation { INSTANCE }
    }

    record StoredRecord(
        UUID id,
        String fingerprint,
        String state,
        UUID leaseToken,
        Instant leaseExpiresAt,
        IdempotencyResponse response
    ) {
        boolean isCompleted() {
            return "COMPLETED".equals(state);
        }
    }
}

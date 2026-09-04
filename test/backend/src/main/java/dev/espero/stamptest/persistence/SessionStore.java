package dev.espero.stamptest.persistence;

import static dev.espero.stamptest.support.DbTime.instant;
import static dev.espero.stamptest.support.DbTime.value;

import dev.espero.stamptest.domain.DomainModels.SessionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SessionStore {

    private static final String SESSION_SELECT = """
        SELECT s.id AS session_id, s.participant_id, p.owner_key, s.csrf_token,
               s.expires_at AS session_expires_at, c.value AS counter_value,
               c.version AS counter_version
        FROM participant_sessions s
        JOIN participants p ON p.id = s.participant_id
        JOIN counters c ON c.participant_id = p.id
        WHERE s.token_hash = :tokenHash
          AND s.expires_at > :now
          AND p.expires_at > :now
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public SessionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SessionRecord> findActiveByTokenHash(String tokenHash, Instant now) {
        return jdbc.query(
            SESSION_SELECT,
            new MapSqlParameterSource().addValue("tokenHash", tokenHash).addValue("now", value(now)),
            (resultSet, rowNumber) -> mapSession(resultSet)
        ).stream().findFirst();
    }

    @Transactional
    public SessionRecord create(
        UUID participantId,
        UUID sessionId,
        String ownerKey,
        String tokenHash,
        String csrfToken,
        Instant now,
        Instant expiresAt
    ) {
        jdbc.update("""
            INSERT INTO participants(id, owner_key, created_at, expires_at)
            VALUES (:id, :ownerKey, :createdAt, :expiresAt)
            """, Map.of(
                "id", participantId,
                "ownerKey", ownerKey,
                "createdAt", value(now),
                "expiresAt", value(expiresAt)
            ));
        jdbc.update("""
            INSERT INTO counters(participant_id, value, version, updated_at)
            VALUES (:participantId, 0, 0, :updatedAt)
            """, Map.of("participantId", participantId, "updatedAt", value(now)));
        jdbc.update("""
            INSERT INTO participant_sessions(
                id, participant_id, token_hash, csrf_token, created_at, expires_at, last_seen_at
            ) VALUES (
                :id, :participantId, :tokenHash, :csrfToken, :createdAt, :expiresAt, :lastSeenAt
            )
            """, Map.of(
                "id", sessionId,
                "participantId", participantId,
                "tokenHash", tokenHash,
                "csrfToken", csrfToken,
                "createdAt", value(now),
                "expiresAt", value(expiresAt),
                "lastSeenAt", value(now)
            ));
        return new SessionRecord(sessionId, participantId, ownerKey, csrfToken, expiresAt, 0, 0);
    }

    public void touch(UUID sessionId, Instant now) {
        jdbc.update(
            "UPDATE participant_sessions SET last_seen_at = :now WHERE id = :id",
            Map.of("now", value(now), "id", sessionId)
        );
    }

    private SessionRecord mapSession(ResultSet resultSet) throws SQLException {
        return new SessionRecord(
            resultSet.getObject("session_id", UUID.class),
            resultSet.getObject("participant_id", UUID.class),
            resultSet.getString("owner_key"),
            resultSet.getString("csrf_token"),
            instant(resultSet, "session_expires_at"),
            resultSet.getInt("counter_value"),
            resultSet.getLong("counter_version")
        );
    }
}

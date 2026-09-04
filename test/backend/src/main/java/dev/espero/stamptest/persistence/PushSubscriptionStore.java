package dev.espero.stamptest.persistence;

import static dev.espero.stamptest.support.DbTime.instant;
import static dev.espero.stamptest.support.DbTime.value;

import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.domain.DomainModels.SubscriptionStatus;
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
public class PushSubscriptionStore {

    private static final String COLUMNS = """
        id, participant_id, endpoint, endpoint_hash, p256dh, auth, expiration_time,
        locale, time_zone, status, created_at, updated_at, last_accepted_at,
        last_failure_at, deactivated_at, deactivation_reason
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public PushSubscriptionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public synchronized PushSubscription upsert(
        UUID participantId,
        String endpoint,
        String endpointHash,
        String p256dh,
        String auth,
        Instant expirationTime,
        String locale,
        String timeZone,
        Instant now
    ) {
        Optional<PushSubscription> existing = findByEndpointHashForUpdate(endpointHash);
        if (existing.isPresent()) {
            UUID id = existing.get().id();
            update(id, participantId, endpoint, p256dh, auth, expirationTime, locale, timeZone, now);
            return findById(id).orElseThrow();
        }
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("participantId", participantId)
            .addValue("endpoint", endpoint)
            .addValue("endpointHash", endpointHash)
            .addValue("p256dh", p256dh)
            .addValue("auth", auth)
            .addValue("expirationTime", value(expirationTime))
            .addValue("locale", locale)
            .addValue("timeZone", timeZone)
            .addValue("createdAt", value(now))
            .addValue("updatedAt", value(now));
        jdbc.update("""
            INSERT INTO push_subscriptions(
                id, participant_id, endpoint, endpoint_hash, p256dh, auth, expiration_time,
                locale, time_zone, status, created_at, updated_at
            ) VALUES (
                :id, :participantId, :endpoint, :endpointHash, :p256dh, :auth, :expirationTime,
                :locale, :timeZone, 'ACTIVE', :createdAt, :updatedAt
            )
            """, parameters);
        return findById(id).orElseThrow();
    }

    public List<PushSubscription> findByParticipant(UUID participantId) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM push_subscriptions WHERE participant_id = :participantId ORDER BY created_at",
            Map.of("participantId", participantId),
            (resultSet, rowNumber) -> map(resultSet)
        );
    }

    public List<PushSubscription> findActiveByParticipant(UUID participantId, Instant now) {
        return jdbc.query("""
            SELECT %s FROM push_subscriptions
            WHERE participant_id = :participantId
              AND status = 'ACTIVE'
              AND (expiration_time IS NULL OR expiration_time > :now)
            ORDER BY created_at
            """.formatted(COLUMNS),
            new MapSqlParameterSource().addValue("participantId", participantId).addValue("now", value(now)),
            (resultSet, rowNumber) -> map(resultSet)
        );
    }

    public Optional<PushSubscription> findById(UUID id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM push_subscriptions WHERE id = :id",
            Map.of("id", id),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    public boolean deactivate(UUID participantId, UUID id, String reason, Instant now) {
        return jdbc.update("""
            UPDATE push_subscriptions
            SET status = 'INACTIVE', deactivated_at = :now,
                deactivation_reason = :reason, updated_at = :now
            WHERE id = :id AND participant_id = :participantId AND status = 'ACTIVE'
            """, Map.of("now", value(now), "reason", reason, "id", id, "participantId", participantId)) > 0;
    }

    public void markAccepted(UUID id, Instant now) {
        jdbc.update("""
            UPDATE push_subscriptions
            SET last_accepted_at = :now, updated_at = :now
            WHERE id = :id
            """, Map.of("now", value(now), "id", id));
    }

    public void markFailure(UUID id, Instant now) {
        jdbc.update("""
            UPDATE push_subscriptions
            SET last_failure_at = :now, updated_at = :now
            WHERE id = :id
            """, Map.of("now", value(now), "id", id));
    }

    public void deactivateExpired(UUID id, String reason, Instant now) {
        jdbc.update("""
            UPDATE push_subscriptions
            SET status = 'INACTIVE', deactivated_at = :now,
                deactivation_reason = :reason, last_failure_at = :now, updated_at = :now
            WHERE id = :id
            """, Map.of("now", value(now), "reason", reason, "id", id));
    }

    private Optional<PushSubscription> findByEndpointHashForUpdate(String endpointHash) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM push_subscriptions WHERE endpoint_hash = :endpointHash FOR UPDATE",
            Map.of("endpointHash", endpointHash),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private void update(
        UUID id,
        UUID participantId,
        String endpoint,
        String p256dh,
        String auth,
        Instant expirationTime,
        String locale,
        String timeZone,
        Instant now
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("participantId", participantId)
            .addValue("endpoint", endpoint)
            .addValue("p256dh", p256dh)
            .addValue("auth", auth)
            .addValue("expirationTime", value(expirationTime))
            .addValue("locale", locale)
            .addValue("timeZone", timeZone)
            .addValue("updatedAt", value(now));
        jdbc.update("""
            UPDATE push_subscriptions
            SET participant_id = :participantId, endpoint = :endpoint, p256dh = :p256dh,
                auth = :auth, expiration_time = :expirationTime, locale = :locale,
                time_zone = :timeZone, status = 'ACTIVE', updated_at = :updatedAt,
                deactivated_at = NULL, deactivation_reason = NULL
            WHERE id = :id
            """, parameters);
    }

    private PushSubscription map(ResultSet resultSet) throws SQLException {
        return new PushSubscription(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("participant_id", UUID.class),
            resultSet.getString("endpoint"),
            resultSet.getString("endpoint_hash"),
            resultSet.getString("p256dh"),
            resultSet.getString("auth"),
            instant(resultSet, "expiration_time"),
            resultSet.getString("locale"),
            resultSet.getString("time_zone"),
            SubscriptionStatus.valueOf(resultSet.getString("status")),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            instant(resultSet, "last_accepted_at"),
            instant(resultSet, "last_failure_at"),
            instant(resultSet, "deactivated_at"),
            resultSet.getString("deactivation_reason")
        );
    }
}

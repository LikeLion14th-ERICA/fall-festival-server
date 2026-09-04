package dev.espero.stamptest.persistence;

import static dev.espero.stamptest.support.DbTime.instant;
import static dev.espero.stamptest.support.DbTime.value;

import dev.espero.stamptest.domain.DomainModels.DeliveryStatus;
import dev.espero.stamptest.domain.DomainModels.AcknowledgementResult;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.NotificationKind;
import dev.espero.stamptest.domain.DomainModels.NotificationStatus;
import dev.espero.stamptest.domain.DomainModels.PushDelivery;
import dev.espero.stamptest.domain.DomainModels.TerminalTransition;
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

@Repository
public class NotificationStore {

    private static final String EVENT_COLUMNS = """
        id, participant_id, run_id, kind, sequence, status, message_id,
        notification_tag, scheduled_at, sent_at, accepted_at, acknowledged_at,
        client_received_at, error_code, terminal_counted, created_at, updated_at
        """;
    private static final String DELIVERY_COLUMNS = """
        id, event_id, subscription_id, status, attempt_count, next_attempt_at,
        response_code, sent_at, accepted_at, error_code, updated_at
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public NotificationEvent createEvent(
        UUID participantId,
        UUID runId,
        NotificationKind kind,
        Integer sequence,
        Instant scheduledAt,
        Instant now
    ) {
        UUID id = UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();
        String notificationTag = runId == null
            ? "test-now-" + messageId
            : "clock-" + runId + "-" + sequence;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("participantId", participantId)
            .addValue("runId", runId)
            .addValue("kind", kind.name())
            .addValue("sequence", sequence)
            .addValue("messageId", messageId)
            .addValue("notificationTag", notificationTag)
            .addValue("scheduledAt", value(scheduledAt))
            .addValue("now", value(now));
        jdbc.update("""
            INSERT INTO notification_events(
                id, participant_id, run_id, kind, sequence, status, message_id,
                notification_tag, scheduled_at, terminal_counted, created_at, updated_at
            ) VALUES (
                :id, :participantId, :runId, :kind, :sequence, 'SCHEDULED', :messageId,
                :notificationTag, :scheduledAt, FALSE, :now, :now
            )
            """, parameters);
        return findEvent(id).orElseThrow();
    }

    public void createDelivery(UUID eventId, UUID subscriptionId, Instant now) {
        jdbc.update("""
            INSERT INTO push_deliveries(
                id, event_id, subscription_id, status, attempt_count,
                next_attempt_at, updated_at
            ) VALUES (
                :id, :eventId, :subscriptionId, 'PENDING', 0, :now, :now
            )
            """, Map.of(
                "id", UUID.randomUUID(),
                "eventId", eventId,
                "subscriptionId", subscriptionId,
                "now", value(now)
            ));
    }

    public Optional<TerminalTransition> markEventFailed(UUID eventId, String errorCode, Instant now) {
        NotificationEvent event = findEventForUpdate(eventId).orElseThrow();
        boolean countTerminal = event.runId() != null && !event.terminalCounted();
        jdbc.update("""
            UPDATE notification_events
            SET status = 'FAILED', error_code = :errorCode,
                terminal_counted = CASE WHEN :countTerminal THEN TRUE ELSE terminal_counted END,
                updated_at = :now
            WHERE id = :id AND status NOT IN ('ACCEPTED', 'ACKNOWLEDGED')
            """, Map.of(
                "errorCode", errorCode,
                "countTerminal", countTerminal,
                "now", value(now),
                "id", eventId
            ));
        return countTerminal
            ? Optional.of(new TerminalTransition(event.runId(), false))
            : Optional.empty();
    }

    public Optional<NotificationEvent> findEvent(UUID id) {
        return jdbc.query(
            "SELECT " + EVENT_COLUMNS + " FROM notification_events WHERE id = :id",
            Map.of("id", id),
            (resultSet, rowNumber) -> mapEvent(resultSet)
        ).stream().findFirst();
    }

    public Optional<NotificationEvent> findEventForUpdate(UUID id) {
        return jdbc.query(
            "SELECT " + EVENT_COLUMNS + " FROM notification_events WHERE id = :id FOR UPDATE",
            Map.of("id", id),
            (resultSet, rowNumber) -> mapEvent(resultSet)
        ).stream().findFirst();
    }

    public List<NotificationEvent> findHistory(UUID participantId, int limit) {
        return jdbc.query("""
            SELECT %s FROM notification_events
            WHERE participant_id = :participantId
            ORDER BY created_at DESC
            LIMIT :limit
            """.formatted(EVENT_COLUMNS),
            new MapSqlParameterSource().addValue("participantId", participantId).addValue("limit", limit),
            (resultSet, rowNumber) -> mapEvent(resultSet));
    }

    public List<UUID> findDueDeliveryIds(Instant now, int limit) {
        return jdbc.query("""
            SELECT id FROM push_deliveries
            WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT :limit
            """, new MapSqlParameterSource().addValue("now", value(now)).addValue("limit", limit),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    }

    public List<UUID> findDeliveryIdsByEvent(UUID eventId) {
        return jdbc.query(
            "SELECT id FROM push_deliveries WHERE event_id = :eventId ORDER BY id",
            Map.of("eventId", eventId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)
        );
    }

    public Optional<PushDelivery> findDeliveryForUpdate(UUID id) {
        return jdbc.query(
            "SELECT " + DELIVERY_COLUMNS + " FROM push_deliveries WHERE id = :id FOR UPDATE",
            Map.of("id", id),
            (resultSet, rowNumber) -> mapDelivery(resultSet)
        ).stream().findFirst();
    }

    public void markEventSending(UUID eventId, Instant sentAt) {
        jdbc.update("""
            UPDATE notification_events
            SET status = CASE WHEN status = 'SCHEDULED' THEN 'SENDING' ELSE status END,
                sent_at = COALESCE(sent_at, :sentAt), updated_at = :sentAt
            WHERE id = :id
            """, Map.of("sentAt", value(sentAt), "id", eventId));
    }

    public void markDeliveryAccepted(UUID deliveryId, int attempt, int responseCode, Instant now) {
        jdbc.update("""
            UPDATE push_deliveries
            SET status = 'ACCEPTED', attempt_count = :attempt, response_code = :responseCode,
                sent_at = :now, accepted_at = :now, error_code = NULL, updated_at = :now
            WHERE id = :id
            """, Map.of(
                "attempt", attempt,
                "responseCode", responseCode,
                "now", value(now),
                "id", deliveryId
            ));
    }

    public void markDeliveryRetry(
        UUID deliveryId,
        int attempt,
        Integer responseCode,
        String errorCode,
        Instant sentAt,
        Instant nextAttemptAt
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", deliveryId)
            .addValue("attempt", attempt)
            .addValue("responseCode", responseCode)
            .addValue("errorCode", errorCode)
            .addValue("sentAt", value(sentAt))
            .addValue("nextAttemptAt", value(nextAttemptAt));
        jdbc.update("""
            UPDATE push_deliveries
            SET status = 'RETRY', attempt_count = :attempt, response_code = :responseCode,
                sent_at = :sentAt, next_attempt_at = :nextAttemptAt,
                error_code = :errorCode, updated_at = :sentAt
            WHERE id = :id
            """, parameters);
    }

    public void markDeliveryFailed(
        UUID deliveryId,
        int attempt,
        Integer responseCode,
        String errorCode,
        Instant now
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", deliveryId)
            .addValue("attempt", attempt)
            .addValue("responseCode", responseCode)
            .addValue("errorCode", errorCode)
            .addValue("now", value(now));
        jdbc.update("""
            UPDATE push_deliveries
            SET status = 'FAILED', attempt_count = :attempt, response_code = :responseCode,
                sent_at = :now, error_code = :errorCode, updated_at = :now
            WHERE id = :id
            """, parameters);
    }

    public Optional<TerminalTransition> refreshEventStatus(UUID eventId, Instant now) {
        NotificationEvent event = findEventForUpdate(eventId).orElseThrow();
        if (event.status() == NotificationStatus.ACKNOWLEDGED) {
            return Optional.empty();
        }
        DeliveryCounts counts = jdbc.queryForObject("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN status = 'ACCEPTED' THEN 1 ELSE 0 END) AS accepted,
                   SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                   MIN(accepted_at) AS first_accepted_at
            FROM push_deliveries WHERE event_id = :eventId
            """, Map.of("eventId", eventId), (resultSet, rowNumber) -> new DeliveryCounts(
                resultSet.getInt("total"),
                resultSet.getInt("accepted"),
                resultSet.getInt("failed"),
                instant(resultSet, "first_accepted_at")
            ));
        NotificationStatus status;
        String errorCode = null;
        if (counts.accepted() > 0) {
            status = NotificationStatus.ACCEPTED;
        } else if (counts.total() > 0 && counts.failed() == counts.total()) {
            status = NotificationStatus.FAILED;
            errorCode = "ALL_DELIVERIES_FAILED";
        } else {
            status = NotificationStatus.SENDING;
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", eventId)
            .addValue("status", status.name())
            .addValue("acceptedAt", value(counts.firstAcceptedAt()))
            .addValue("errorCode", errorCode)
            .addValue("now", value(now));
        jdbc.update("""
            UPDATE notification_events
            SET status = :status, accepted_at = COALESCE(accepted_at, :acceptedAt),
                error_code = :errorCode, updated_at = :now
            WHERE id = :id
            """, parameters);
        boolean terminal = status == NotificationStatus.ACCEPTED || status == NotificationStatus.FAILED;
        if (terminal && !event.terminalCounted() && event.runId() != null) {
            jdbc.update(
                "UPDATE notification_events SET terminal_counted = TRUE WHERE id = :id",
                Map.of("id", eventId)
            );
            return Optional.of(new TerminalTransition(event.runId(), status == NotificationStatus.ACCEPTED));
        }
        return Optional.empty();
    }

    public Optional<AcknowledgementResult> acknowledge(
        UUID eventId,
        Instant clientReceivedAt,
        Instant acknowledgedAt
    ) {
        NotificationEvent before = findEventForUpdate(eventId).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        boolean countTerminal = before.runId() != null && !before.terminalCounted();
        int updated = jdbc.update("""
            UPDATE notification_events
            SET status = 'ACKNOWLEDGED', acknowledged_at = COALESCE(acknowledged_at, :acknowledgedAt),
                client_received_at = COALESCE(client_received_at, :clientReceivedAt),
                terminal_counted = CASE WHEN :countTerminal THEN TRUE ELSE terminal_counted END,
                updated_at = :acknowledgedAt
            WHERE id = :id AND status IN ('SENDING', 'ACCEPTED', 'ACKNOWLEDGED')
            """, new MapSqlParameterSource()
            .addValue("id", eventId)
            .addValue("acknowledgedAt", value(acknowledgedAt))
            .addValue("clientReceivedAt", value(clientReceivedAt))
            .addValue("countTerminal", countTerminal));
        if (updated == 0) {
            return Optional.empty();
        }
        NotificationEvent event = findEvent(eventId).orElseThrow();
        TerminalTransition transition = countTerminal ? new TerminalTransition(before.runId(), true) : null;
        return Optional.of(new AcknowledgementResult(event, transition));
    }

    private NotificationEvent mapEvent(ResultSet resultSet) throws SQLException {
        int rawSequence = resultSet.getInt("sequence");
        Integer sequence = resultSet.wasNull() ? null : rawSequence;
        return new NotificationEvent(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("participant_id", UUID.class),
            resultSet.getObject("run_id", UUID.class),
            NotificationKind.valueOf(resultSet.getString("kind")),
            sequence,
            NotificationStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("message_id"),
            resultSet.getString("notification_tag"),
            instant(resultSet, "scheduled_at"),
            instant(resultSet, "sent_at"),
            instant(resultSet, "accepted_at"),
            instant(resultSet, "acknowledged_at"),
            instant(resultSet, "client_received_at"),
            resultSet.getString("error_code"),
            resultSet.getBoolean("terminal_counted"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at")
        );
    }

    private PushDelivery mapDelivery(ResultSet resultSet) throws SQLException {
        int rawResponseCode = resultSet.getInt("response_code");
        Integer responseCode = resultSet.wasNull() ? null : rawResponseCode;
        return new PushDelivery(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("event_id", UUID.class),
            resultSet.getObject("subscription_id", UUID.class),
            DeliveryStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("attempt_count"),
            instant(resultSet, "next_attempt_at"),
            responseCode,
            instant(resultSet, "sent_at"),
            instant(resultSet, "accepted_at"),
            resultSet.getString("error_code"),
            instant(resultSet, "updated_at")
        );
    }

    private record DeliveryCounts(int total, int accepted, int failed, Instant firstAcceptedAt) {}
}

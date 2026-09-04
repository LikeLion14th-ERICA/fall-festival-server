package dev.espero.stamptest.domain;

import java.time.Instant;
import java.util.UUID;

public final class DomainModels {

    private DomainModels() {}

    public enum SubscriptionStatus { ACTIVE, INACTIVE }
    public enum ClockStatus { ACTIVE, COMPLETED, STOPPED }
    public enum NotificationKind { TEST_NOW, CLOCK }
    public enum NotificationStatus { SCHEDULED, SENDING, ACCEPTED, FAILED, ACKNOWLEDGED }
    public enum DeliveryStatus { PENDING, RETRY, ACCEPTED, FAILED }

    public record SessionRecord(
        UUID sessionId,
        UUID participantId,
        String ownerKey,
        String csrfToken,
        Instant sessionExpiresAt,
        int counterValue,
        long counterVersion
    ) {}

    public record NewSession(SessionRecord session, String rawSessionToken) {}

    public record CounterResult(int value, long version, boolean duplicate) {}

    public record PushSubscription(
        UUID id,
        UUID participantId,
        String endpoint,
        String endpointHash,
        String p256dh,
        String auth,
        Instant expirationTime,
        String locale,
        String timeZone,
        SubscriptionStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastAcceptedAt,
        Instant lastFailureAt,
        Instant deactivatedAt,
        String deactivationReason
    ) {}

    public record ClockTestRun(
        UUID id,
        UUID participantId,
        ClockStatus status,
        Instant startedAt,
        Instant endsAt,
        Instant nextScheduledAt,
        int nextSequence,
        int sentCount,
        int failedCount,
        Instant updatedAt
    ) {}

    public record NotificationEvent(
        UUID id,
        UUID participantId,
        UUID runId,
        NotificationKind kind,
        Integer sequence,
        NotificationStatus status,
        String messageId,
        String notificationTag,
        Instant scheduledAt,
        Instant sentAt,
        Instant acceptedAt,
        Instant acknowledgedAt,
        Instant clientReceivedAt,
        String errorCode,
        boolean terminalCounted,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record PushDelivery(
        UUID id,
        UUID eventId,
        UUID subscriptionId,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        Integer responseCode,
        Instant sentAt,
        Instant acceptedAt,
        String errorCode,
        Instant updatedAt
    ) {}

    public record TerminalTransition(UUID runId, boolean accepted) {}

    public record AcknowledgementResult(NotificationEvent event, TerminalTransition transition) {}
}

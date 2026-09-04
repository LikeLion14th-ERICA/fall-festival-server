package dev.espero.stamptest.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {

    private ApiDtos() {}

    public record SessionRequest(@Size(max = 256) String accessCode) {}

    public record SessionResponse(String csrfToken, String ownerKey, StateResponse state) {}

    public record PublicConfigResponse(
        String vapidPublicKey,
        boolean pushEnabled,
        String clockZone,
        int clockTestDurationMinutes,
        int clockTestNotificationCount
    ) {}

    public record StateResponse(
        CounterDto counter,
        List<PushSubscriptionDto> pushSubscriptions,
        ClockTestDto clockTest,
        List<NotificationDto> notificationHistory,
        OffsetDateTime serverTime
    ) {}

    public record CounterOperationRequest(@NotNull UUID operationId, @NotNull @Min(-1) @Max(1) Integer delta) {}

    public record CounterOperationResponse(CounterDto counter, boolean duplicate) {}

    public record CounterDto(int value, long version) {}

    public record PushSubscriptionRequest(
        @NotBlank @Size(max = 4096) String endpoint,
        Long expirationTime,
        @NotNull @Valid PushKeysRequest keys,
        @Size(max = 32) String locale,
        @Size(max = 64) String timeZone
    ) {}

    public record PushKeysRequest(
        @NotBlank @Size(max = 256) String p256dh,
        @NotBlank @Size(max = 128) String auth
    ) {}

    public record PushSubscriptionResponse(PushSubscriptionDto subscription) {}

    public record PushSubscriptionDto(
        UUID id,
        String status,
        OffsetDateTime expirationTime,
        String locale,
        String timeZone,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastAcceptedAt,
        OffsetDateTime deactivatedAt,
        String deactivationReason
    ) {}

    public record NotificationResponse(NotificationDto notification) {}

    public record NotificationAckRequest(
        @NotNull UUID notificationId,
        @NotBlank @Size(max = 128) String ackToken,
        @NotNull OffsetDateTime receivedAt
    ) {}

    public record NotificationDto(
        UUID id,
        String kind,
        Integer sequence,
        String status,
        String messageId,
        String notificationTag,
        OffsetDateTime scheduledAt,
        OffsetDateTime sentAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime acknowledgedAt,
        String errorCode
    ) {}

    public record ClockTestRequest(@NotNull @Min(5) @Max(5) Integer durationMinutes) {}

    public record ClockTestResponse(ClockTestDto clockTest) {}

    public record ClockTestDto(
        String status,
        UUID runId,
        OffsetDateTime startedAt,
        OffsetDateTime endsAt,
        OffsetDateTime nextScheduledAt,
        int sentCount,
        int failedCount
    ) {}
}

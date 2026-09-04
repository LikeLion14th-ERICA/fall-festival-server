package dev.espero.stamptest.service;

import dev.espero.stamptest.config.SecurityProperties;
import dev.espero.stamptest.domain.DomainModels.AcknowledgementResult;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.NotificationKind;
import dev.espero.stamptest.domain.DomainModels.TerminalTransition;
import dev.espero.stamptest.persistence.ClockTestStore;
import dev.espero.stamptest.persistence.NotificationStore;
import dev.espero.stamptest.support.FixedWindowRateLimiter;
import dev.espero.stamptest.support.TokenSupport;
import dev.espero.stamptest.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationCreationService creation;
    private final PushDeliveryWorker deliveryWorker;
    private final NotificationStore notifications;
    private final ClockTestStore clockTests;
    private final TokenSupport tokens;
    private final FixedWindowRateLimiter rateLimiter;
    private final SecurityProperties security;
    private final Clock clock;

    public NotificationService(
        NotificationCreationService creation,
        PushDeliveryWorker deliveryWorker,
        NotificationStore notifications,
        ClockTestStore clockTests,
        TokenSupport tokens,
        FixedWindowRateLimiter rateLimiter,
        SecurityProperties security,
        Clock clock
    ) {
        this.creation = creation;
        this.deliveryWorker = deliveryWorker;
        this.notifications = notifications;
        this.clockTests = clockTests;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.security = security;
        this.clock = clock;
    }

    public NotificationEvent sendTestNow(UUID participantId) {
        Instant now = clock.instant();
        NotificationEvent event = creation.create(
            participantId, null, NotificationKind.TEST_NOW, null, now
        );
        deliveryWorker.processEvent(event.id());
        return notifications.findEvent(event.id()).orElseThrow();
    }

    @Transactional
    public NotificationEvent acknowledge(
        UUID notificationId,
        String ackToken,
        OffsetDateTime clientReceivedAt,
        String clientIp
    ) {
        FixedWindowRateLimiter.Decision decision = rateLimiter.acquire(
            "notification-ack:" + clientIp,
            security.ackMaxRequests(),
            security.ackWindow()
        );
        if (!decision.allowed()) {
            throw new RateLimitedException(
                "ACK_RATE_LIMITED",
                "Too many notification acknowledgement attempts.",
                decision.retryAfterSeconds()
            );
        }
        if (!tokens.configured() || !tokens.validAckToken(notificationId, ackToken)) {
            throw ackNotFound();
        }
        Instant now = clock.instant();
        Instant receivedAt = clientReceivedAt.toInstant();
        if (receivedAt.isAfter(now.plusSeconds(300)) || receivedAt.isBefore(now.minusSeconds(86_400))) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "ACK_RECEIVED_AT_INVALID",
                "receivedAt is outside the accepted clock-skew window."
            );
        }
        Optional<AcknowledgementResult> result = notifications.acknowledge(notificationId, receivedAt, now);
        if (result.isEmpty()) {
            throw ackNotFound();
        }
        TerminalTransition transition = result.get().transition();
        if (transition != null) {
            clockTests.incrementTerminalCount(transition.runId(), transition.accepted(), now);
        }
        return result.get().event();
    }

    private ApiException ackNotFound() {
        return new ApiException(
            HttpStatus.NOT_FOUND,
            "NOTIFICATION_ACK_NOT_FOUND",
            "The notification or acknowledgement token is invalid."
        );
    }
}

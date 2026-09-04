package dev.espero.stamptest.service;

import dev.espero.stamptest.config.PushProperties;
import dev.espero.stamptest.domain.DomainModels.DeliveryStatus;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.NotificationStatus;
import dev.espero.stamptest.domain.DomainModels.PushDelivery;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.domain.DomainModels.SubscriptionStatus;
import dev.espero.stamptest.domain.DomainModels.TerminalTransition;
import dev.espero.stamptest.persistence.ClockTestStore;
import dev.espero.stamptest.persistence.NotificationStore;
import dev.espero.stamptest.persistence.PushSubscriptionStore;
import dev.espero.stamptest.push.PushGateway;
import dev.espero.stamptest.push.PushGateway.PushResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PushDeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(PushDeliveryProcessor.class);
    private static final String IN_FLIGHT = "DELIVERY_IN_FLIGHT";

    private final NotificationStore notifications;
    private final PushSubscriptionStore subscriptions;
    private final ClockTestStore clockTests;
    private final PushGateway gateway;
    private final NotificationPayloadFactory payloads;
    private final PushProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PushDeliveryProcessor(
        NotificationStore notifications,
        PushSubscriptionStore subscriptions,
        ClockTestStore clockTests,
        PushGateway gateway,
        NotificationPayloadFactory payloads,
        PushProperties properties,
        Clock clock,
        TransactionTemplate transactions
    ) {
        this.notifications = notifications;
        this.subscriptions = subscriptions;
        this.clockTests = clockTests;
        this.gateway = gateway;
        this.payloads = payloads;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
    }

    public void process(UUID deliveryId) {
        DeliveryAttempt claimed = transactions.execute(status -> claim(deliveryId, clock.instant()));
        if (claimed == null) {
            return;
        }
        PushResult result;
        try {
            Duration remainingTtl = Duration.between(
                claimed.claimedAt(),
                claimed.event().scheduledAt().plus(properties.ttl())
            );
            result = gateway.send(
                claimed.subscription(),
                payloads.create(claimed.event(), claimed.subscription(), claimed.claimedAt()),
                remainingTtl
            );
        } catch (Exception exception) {
            Instant completedAt = clock.instant();
            transactions.executeWithoutResult(status -> finishTransportError(claimed, completedAt));
            log.warn(
                "Push transport error: deliveryId={} eventId={} attempt={} errorType={}",
                claimed.deliveryId(), claimed.event().id(), claimed.attempt(), exception.getClass().getSimpleName()
            );
            return;
        }
        Instant completedAt = clock.instant();
        transactions.executeWithoutResult(status -> finishResult(claimed, result, completedAt));
    }

    private DeliveryAttempt claim(UUID deliveryId, Instant now) {
        PushDelivery delivery = notifications.findDeliveryForUpdate(deliveryId).orElse(null);
        if (delivery == null
            || (delivery.status() != DeliveryStatus.PENDING && delivery.status() != DeliveryStatus.RETRY)
            || delivery.nextAttemptAt().isAfter(now)) {
            return null;
        }
        NotificationEvent event = notifications.findEvent(delivery.eventId()).orElseThrow();
        if (event.status() == NotificationStatus.ACKNOWLEDGED) {
            notifications.markDeliveryFailed(delivery.id(), delivery.attemptCount(), null, "ALREADY_ACKNOWLEDGED", now);
            return null;
        }
        PushSubscription subscription = subscriptions.findById(delivery.subscriptionId()).orElse(null);
        if (subscription == null || subscription.status() != SubscriptionStatus.ACTIVE) {
            fail(delivery, null, "SUBSCRIPTION_INACTIVE", now);
            return null;
        }
        if (!subscription.participantId().equals(event.participantId())) {
            fail(delivery, null, "SUBSCRIPTION_OWNER_MISMATCH", now);
            return null;
        }
        if (!now.isBefore(event.scheduledAt().plus(properties.ttl()))) {
            fail(delivery, null, "TTL_EXPIRED", now);
            return null;
        }
        if (!properties.configured()) {
            log.warn("Push delivery disabled or not configured: deliveryId={} eventId={}", delivery.id(), event.id());
            fail(delivery, null, "PUSH_NOT_CONFIGURED", now);
            return null;
        }

        int attempt = delivery.attemptCount() + 1;
        notifications.markEventSending(event.id(), now);
        Instant leaseUntil = min(
            now.plus(properties.requestTimeout()).plusSeconds(5),
            event.scheduledAt().plus(properties.ttl())
        );
        notifications.markDeliveryRetry(
            delivery.id(), attempt, null, IN_FLIGHT, now, leaseUntil
        );
        return new DeliveryAttempt(delivery.id(), event, subscription, attempt, now);
    }

    private void finishResult(DeliveryAttempt claimed, PushResult result, Instant now) {
        if (!stillOwnsClaim(claimed)) {
            return;
        }
        if (result.statusCode() >= 200 && result.statusCode() < 300) {
            notifications.markDeliveryAccepted(claimed.deliveryId(), claimed.attempt(), result.statusCode(), now);
            subscriptions.markAccepted(claimed.subscription().id(), now);
            log.info(
                "Push service accepted delivery: deliveryId={} eventId={} status={} attempt={}",
                claimed.deliveryId(), claimed.event().id(), result.statusCode(), claimed.attempt()
            );
        } else if (result.statusCode() == 404 || result.statusCode() == 410) {
            notifications.markDeliveryFailed(
                claimed.deliveryId(), claimed.attempt(), result.statusCode(), "SUBSCRIPTION_GONE", now
            );
            subscriptions.deactivateExpired(
                claimed.subscription().id(), "PUSH_SERVICE_" + result.statusCode(), now
            );
            log.warn(
                "Push subscription deactivated: deliveryId={} eventId={} subscriptionId={} status={}",
                claimed.deliveryId(), claimed.event().id(), claimed.subscription().id(), result.statusCode()
            );
        } else {
            Duration delay = result.retryAfter() == null ? retryDelay(claimed.attempt()) : result.retryAfter();
            if (retryable(result.statusCode()) && canRetry(claimed.event(), claimed.attempt(), now, delay)) {
                Instant retryAt = now.plus(delay);
                notifications.markDeliveryRetry(
                    claimed.deliveryId(), claimed.attempt(), result.statusCode(),
                    "PUSH_SERVICE_" + result.statusCode(), now, retryAt
                );
                subscriptions.markFailure(claimed.subscription().id(), now);
                log.warn(
                    "Push delivery scheduled for retry: deliveryId={} eventId={} status={} attempt={} retryAt={}",
                    claimed.deliveryId(), claimed.event().id(), result.statusCode(), claimed.attempt(), retryAt
                );
            } else {
                notifications.markDeliveryFailed(
                    claimed.deliveryId(), claimed.attempt(), result.statusCode(),
                    "PUSH_SERVICE_" + result.statusCode(), now
                );
                subscriptions.markFailure(claimed.subscription().id(), now);
                log.warn(
                    "Push delivery failed: deliveryId={} eventId={} status={} attempt={}",
                    claimed.deliveryId(), claimed.event().id(), result.statusCode(), claimed.attempt()
                );
            }
        }
        applyTerminalTransition(notifications.refreshEventStatus(claimed.event().id(), now), now);
    }

    private void finishTransportError(DeliveryAttempt claimed, Instant now) {
        if (!stillOwnsClaim(claimed)) {
            return;
        }
        Duration delay = retryDelay(claimed.attempt());
        if (canRetry(claimed.event(), claimed.attempt(), now, delay)) {
            notifications.markDeliveryRetry(
                claimed.deliveryId(), claimed.attempt(), null, "PUSH_TRANSPORT_ERROR", now, now.plus(delay)
            );
        } else {
            notifications.markDeliveryFailed(
                claimed.deliveryId(), claimed.attempt(), null, "PUSH_TRANSPORT_ERROR", now
            );
        }
        subscriptions.markFailure(claimed.subscription().id(), now);
        applyTerminalTransition(notifications.refreshEventStatus(claimed.event().id(), now), now);
    }

    private boolean stillOwnsClaim(DeliveryAttempt claimed) {
        PushDelivery current = notifications.findDeliveryForUpdate(claimed.deliveryId()).orElse(null);
        return current != null
            && current.status() == DeliveryStatus.RETRY
            && current.attemptCount() == claimed.attempt()
            && Objects.equals(current.errorCode(), IN_FLIGHT);
    }

    private void fail(PushDelivery delivery, Integer responseCode, String errorCode, Instant now) {
        notifications.markDeliveryFailed(
            delivery.id(),
            delivery.attemptCount(),
            responseCode,
            errorCode,
            now
        );
        applyTerminalTransition(notifications.refreshEventStatus(delivery.eventId(), now), now);
    }

    private void applyTerminalTransition(Optional<TerminalTransition> transition, Instant now) {
        transition.ifPresent(value -> clockTests.incrementTerminalCount(value.runId(), value.accepted(), now));
    }

    private boolean retryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private boolean canRetry(NotificationEvent event, int attempt, Instant now, Duration delay) {
        return attempt < properties.maxAttempts()
            && delay != null
            && !delay.isNegative()
            && now.plus(delay).isBefore(event.scheduledAt().plus(properties.ttl()));
    }

    private Duration retryDelay(int attempt) {
        return attempt <= 1 ? Duration.ofSeconds(5) : Duration.ofSeconds(15);
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private record DeliveryAttempt(
        UUID deliveryId,
        NotificationEvent event,
        PushSubscription subscription,
        int attempt,
        Instant claimedAt
    ) {}
}

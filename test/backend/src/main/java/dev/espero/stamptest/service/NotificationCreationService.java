package dev.espero.stamptest.service;

import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.NotificationKind;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.domain.DomainModels.TerminalTransition;
import dev.espero.stamptest.persistence.ClockTestStore;
import dev.espero.stamptest.persistence.NotificationStore;
import dev.espero.stamptest.persistence.PushSubscriptionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationCreationService {

    private final NotificationStore notifications;
    private final PushSubscriptionStore subscriptions;
    private final ClockTestStore clockTests;
    private final Clock clock;

    public NotificationCreationService(
        NotificationStore notifications,
        PushSubscriptionStore subscriptions,
        ClockTestStore clockTests,
        Clock clock
    ) {
        this.notifications = notifications;
        this.subscriptions = subscriptions;
        this.clockTests = clockTests;
        this.clock = clock;
    }

    @Transactional
    public NotificationEvent create(
        UUID participantId,
        UUID runId,
        NotificationKind kind,
        Integer sequence,
        Instant scheduledAt
    ) {
        Instant now = clock.instant();
        NotificationEvent event = notifications.createEvent(
            participantId,
            runId,
            kind,
            sequence,
            scheduledAt,
            now
        );
        List<PushSubscription> active = subscriptions.findActiveByParticipant(participantId, now);
        for (PushSubscription subscription : active) {
            notifications.createDelivery(event.id(), subscription.id(), now);
        }
        if (active.isEmpty()) {
            Optional<TerminalTransition> transition = notifications.markEventFailed(
                event.id(),
                "NO_ACTIVE_SUBSCRIPTION",
                now
            );
            transition.ifPresent(value -> clockTests.incrementTerminalCount(value.runId(), false, now));
        }
        return notifications.findEvent(event.id()).orElseThrow();
    }
}

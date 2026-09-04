package dev.espero.stamptest.service;

import dev.espero.stamptest.domain.DomainModels.ClockTestRun;
import dev.espero.stamptest.domain.DomainModels.CounterResult;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.persistence.ClockTestStore;
import dev.espero.stamptest.persistence.CounterStore;
import dev.espero.stamptest.persistence.NotificationStore;
import dev.espero.stamptest.persistence.PushSubscriptionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StateService {

    private final CounterStore counters;
    private final PushSubscriptionStore subscriptions;
    private final ClockTestStore clockTests;
    private final NotificationStore notifications;
    private final Clock clock;

    public StateService(
        CounterStore counters,
        PushSubscriptionStore subscriptions,
        ClockTestStore clockTests,
        NotificationStore notifications,
        Clock clock
    ) {
        this.counters = counters;
        this.subscriptions = subscriptions;
        this.clockTests = clockTests;
        this.notifications = notifications;
        this.clock = clock;
    }

    public CurrentState get(UUID participantId) {
        return new CurrentState(
            counters.get(participantId),
            subscriptions.findByParticipant(participantId),
            clockTests.findLatest(participantId),
            notifications.findHistory(participantId, 25),
            clock.instant()
        );
    }

    public record CurrentState(
        CounterResult counter,
        List<PushSubscription> pushSubscriptions,
        Optional<ClockTestRun> clockTest,
        List<NotificationEvent> notificationHistory,
        Instant serverTime
    ) {}
}

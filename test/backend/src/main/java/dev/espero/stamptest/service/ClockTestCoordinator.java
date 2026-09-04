package dev.espero.stamptest.service;

import dev.espero.stamptest.config.ClockTestProperties;
import dev.espero.stamptest.domain.DomainModels.ClockStatus;
import dev.espero.stamptest.domain.DomainModels.ClockTestRun;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.NotificationKind;
import dev.espero.stamptest.domain.DomainModels.TerminalTransition;
import dev.espero.stamptest.persistence.ClockTestStore;
import dev.espero.stamptest.persistence.NotificationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClockTestCoordinator {

    private final ClockTestStore runs;
    private final NotificationStore notifications;
    private final NotificationCreationService creation;
    private final ClockTestProperties properties;
    private final Clock clock;

    public ClockTestCoordinator(
        ClockTestStore runs,
        NotificationStore notifications,
        NotificationCreationService creation,
        ClockTestProperties properties,
        Clock clock
    ) {
        this.runs = runs;
        this.notifications = notifications;
        this.creation = creation;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<UUID> processDueRun(UUID runId) {
        ClockTestRun run = runs.findByIdForUpdate(runId).orElse(null);
        if (run == null || run.status() != ClockStatus.ACTIVE || run.nextScheduledAt() == null) {
            return List.of();
        }
        Instant now = clock.instant();
        int sequence = run.nextSequence();
        Instant scheduledAt = run.nextScheduledAt();
        List<UUID> sendableEvents = new ArrayList<>();

        while (sequence <= properties.notificationCount()
            && !scheduledAt.isAfter(now)
            && !now.isBefore(scheduledAt.plus(properties.interval()))) {
            NotificationEvent missed = notifications.createEvent(
                run.participantId(), run.id(), NotificationKind.CLOCK, sequence, scheduledAt, now
            );
            Optional<TerminalTransition> transition = notifications.markEventFailed(
                missed.id(), "MISSED_SCHEDULE", now
            );
            transition.ifPresent(value -> runs.incrementTerminalCount(value.runId(), false, now));
            sequence++;
            scheduledAt = scheduledAt.plus(properties.interval());
        }

        if (sequence <= properties.notificationCount() && !scheduledAt.isAfter(now)) {
            NotificationEvent event = creation.create(
                run.participantId(), run.id(), NotificationKind.CLOCK, sequence, scheduledAt
            );
            sendableEvents.add(event.id());
            sequence++;
            scheduledAt = scheduledAt.plus(properties.interval());
        }

        ClockStatus status = sequence > properties.notificationCount() ? ClockStatus.COMPLETED : ClockStatus.ACTIVE;
        runs.advance(
            run.id(),
            sequence,
            status == ClockStatus.COMPLETED ? null : scheduledAt,
            status,
            now
        );
        return sendableEvents;
    }
}

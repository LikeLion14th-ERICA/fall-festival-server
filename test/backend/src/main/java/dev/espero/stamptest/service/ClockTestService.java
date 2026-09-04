package dev.espero.stamptest.service;

import dev.espero.stamptest.config.ClockTestProperties;
import dev.espero.stamptest.domain.DomainModels.ClockTestRun;
import dev.espero.stamptest.persistence.ClockTestStore;
import dev.espero.stamptest.persistence.PushSubscriptionStore;
import dev.espero.stamptest.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ClockTestService {

    private final ClockTestStore runs;
    private final PushSubscriptionStore subscriptions;
    private final ClockTestProperties properties;
    private final Clock clock;

    public ClockTestService(
        ClockTestStore runs,
        PushSubscriptionStore subscriptions,
        ClockTestProperties properties,
        Clock clock
    ) {
        this.runs = runs;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.clock = clock;
    }

    public ClockTestRun start(UUID participantId, int durationMinutes) {
        int configuredMinutes = Math.toIntExact(properties.duration().toMinutes());
        if (durationMinutes != configuredMinutes) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "CLOCK_TEST_DURATION_INVALID",
                "durationMinutes must be exactly " + configuredMinutes + "."
            );
        }
        Instant now = clock.instant();
        if (subscriptions.findActiveByParticipant(participantId, now).isEmpty()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "ACTIVE_PUSH_SUBSCRIPTION_REQUIRED",
                "Enable a push subscription before starting the clock test."
            );
        }
        return runs.start(
            participantId,
            now,
            now.plus(properties.duration()),
            now.plus(properties.interval())
        );
    }

    public Optional<ClockTestRun> current(UUID participantId) {
        return runs.findLatest(participantId);
    }

    public Optional<ClockTestRun> stop(UUID participantId) {
        return runs.stop(participantId, clock.instant());
    }
}

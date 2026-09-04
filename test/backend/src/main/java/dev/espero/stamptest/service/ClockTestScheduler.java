package dev.espero.stamptest.service;

import dev.espero.stamptest.persistence.ClockTestStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ClockTestScheduler {

    private final ClockTestStore runs;
    private final ClockTestCoordinator coordinator;
    private final PushDeliveryWorker deliveryWorker;
    private final Clock clock;

    public ClockTestScheduler(
        ClockTestStore runs,
        ClockTestCoordinator coordinator,
        PushDeliveryWorker deliveryWorker,
        Clock clock
    ) {
        this.runs = runs;
        this.coordinator = coordinator;
        this.deliveryWorker = deliveryWorker;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.clock-test.scheduler-delay:PT5S}")
    public void tick() {
        for (UUID runId : runs.findDueRunIds(clock.instant(), 100)) {
            for (UUID eventId : coordinator.processDueRun(runId)) {
                deliveryWorker.processEvent(eventId);
            }
        }
    }
}

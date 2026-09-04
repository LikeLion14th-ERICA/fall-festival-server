package dev.espero.stamptest.service;

import dev.espero.stamptest.persistence.NotificationStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PushDeliveryWorker {

    private final NotificationStore notifications;
    private final PushDeliveryProcessor processor;
    private final Clock clock;

    public PushDeliveryWorker(NotificationStore notifications, PushDeliveryProcessor processor, Clock clock) {
        this.notifications = notifications;
        this.processor = processor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.push.worker-delay:PT5S}")
    public void processDue() {
        for (UUID deliveryId : notifications.findDueDeliveryIds(clock.instant(), 100)) {
            processor.process(deliveryId);
        }
    }

    public void processEvent(UUID eventId) {
        for (UUID deliveryId : notifications.findDeliveryIdsByEvent(eventId)) {
            processor.process(deliveryId);
        }
    }
}

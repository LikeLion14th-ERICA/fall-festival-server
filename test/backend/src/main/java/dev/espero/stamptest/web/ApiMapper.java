package dev.espero.stamptest.web;

import dev.espero.stamptest.config.ClockTestProperties;
import dev.espero.stamptest.domain.DomainModels.ClockTestRun;
import dev.espero.stamptest.domain.DomainModels.CounterResult;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.service.StateService.CurrentState;
import dev.espero.stamptest.web.ApiDtos.ClockTestDto;
import dev.espero.stamptest.web.ApiDtos.CounterDto;
import dev.espero.stamptest.web.ApiDtos.NotificationDto;
import dev.espero.stamptest.web.ApiDtos.PushSubscriptionDto;
import dev.espero.stamptest.web.ApiDtos.StateResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public class ApiMapper {

    private final ClockTestProperties properties;

    public ApiMapper(ClockTestProperties properties) {
        this.properties = properties;
    }

    public StateResponse state(CurrentState state) {
        return new StateResponse(
            counter(state.counter()),
            state.pushSubscriptions().stream().map(this::subscription).toList(),
            state.clockTest().map(this::clockTest).orElse(null),
            state.notificationHistory().stream().map(this::notification).toList(),
            time(state.serverTime())
        );
    }

    public CounterDto counter(CounterResult counter) {
        return new CounterDto(counter.value(), counter.version());
    }

    public PushSubscriptionDto subscription(PushSubscription subscription) {
        return new PushSubscriptionDto(
            subscription.id(),
            subscription.status().name(),
            time(subscription.expirationTime()),
            subscription.locale(),
            subscription.timeZone(),
            time(subscription.createdAt()),
            time(subscription.updatedAt()),
            time(subscription.lastAcceptedAt()),
            time(subscription.deactivatedAt()),
            subscription.deactivationReason()
        );
    }

    public ClockTestDto clockTest(ClockTestRun run) {
        return new ClockTestDto(
            run.status().name(),
            run.id(),
            time(run.startedAt()),
            time(run.endsAt()),
            time(run.nextScheduledAt()),
            run.sentCount(),
            run.failedCount()
        );
    }

    public NotificationDto notification(NotificationEvent event) {
        return new NotificationDto(
            event.id(),
            event.kind().name(),
            event.sequence(),
            event.status().name(),
            event.messageId(),
            event.notificationTag(),
            time(event.scheduledAt()),
            time(event.sentAt()),
            time(event.acceptedAt()),
            time(event.acknowledgedAt()),
            event.errorCode()
        );
    }

    public OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, properties.zone());
    }
}

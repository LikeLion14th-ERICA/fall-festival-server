package dev.espero.stamptest.web;

import dev.espero.stamptest.domain.DomainModels.ClockTestRun;
import dev.espero.stamptest.domain.DomainModels.CounterResult;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.service.ClockTestService;
import dev.espero.stamptest.service.CounterService;
import dev.espero.stamptest.service.NotificationService;
import dev.espero.stamptest.service.PushSubscriptionService;
import dev.espero.stamptest.service.StateService;
import dev.espero.stamptest.web.ApiDtos.ClockTestRequest;
import dev.espero.stamptest.web.ApiDtos.ClockTestResponse;
import dev.espero.stamptest.web.ApiDtos.CounterOperationRequest;
import dev.espero.stamptest.web.ApiDtos.CounterOperationResponse;
import dev.espero.stamptest.web.ApiDtos.NotificationAckRequest;
import dev.espero.stamptest.web.ApiDtos.NotificationResponse;
import dev.espero.stamptest.web.ApiDtos.PushSubscriptionRequest;
import dev.espero.stamptest.web.ApiDtos.PushSubscriptionResponse;
import dev.espero.stamptest.web.ApiDtos.StateResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-api/me")
public class MeController {

    private final StateService states;
    private final CounterService counters;
    private final PushSubscriptionService subscriptions;
    private final NotificationService notifications;
    private final ClockTestService clockTests;
    private final ApiMapper mapper;

    public MeController(
        StateService states,
        CounterService counters,
        PushSubscriptionService subscriptions,
        NotificationService notifications,
        ClockTestService clockTests,
        ApiMapper mapper
    ) {
        this.states = states;
        this.counters = counters;
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.clockTests = clockTests;
        this.mapper = mapper;
    }

    @GetMapping("/state")
    public StateResponse state(HttpServletRequest request) {
        return mapper.state(states.get(participantId(request)));
    }

    @PostMapping("/counter-operations")
    public CounterOperationResponse adjustCounter(
        @Valid @RequestBody CounterOperationRequest body,
        HttpServletRequest request
    ) {
        if (body.delta() != -1 && body.delta() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELTA_INVALID", "delta must be either -1 or 1.");
        }
        CounterResult result = counters.adjust(participantId(request), body.operationId(), body.delta());
        return new CounterOperationResponse(mapper.counter(result), result.duplicate());
    }

    @PostMapping("/push-subscriptions")
    public PushSubscriptionResponse upsertSubscription(
        @Valid @RequestBody PushSubscriptionRequest body,
        HttpServletRequest request
    ) {
        PushSubscription subscription = subscriptions.upsert(
            participantId(request),
            body.endpoint(),
            body.keys().p256dh(),
            body.keys().auth(),
            body.expirationTime(),
            body.locale(),
            body.timeZone()
        );
        return new PushSubscriptionResponse(mapper.subscription(subscription));
    }

    @DeleteMapping("/push-subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubscription(@PathVariable UUID subscriptionId, HttpServletRequest request) {
        subscriptions.deactivate(participantId(request), subscriptionId);
    }

    @PostMapping("/notifications/test-now")
    public NotificationResponse testNow(HttpServletRequest request) {
        NotificationEvent event = notifications.sendTestNow(participantId(request));
        return new NotificationResponse(mapper.notification(event));
    }

    @PostMapping("/notifications/ack")
    public NotificationResponse acknowledge(
        @Valid @RequestBody NotificationAckRequest body,
        HttpServletRequest request
    ) {
        NotificationEvent event = notifications.acknowledge(
            body.notificationId(),
            body.ackToken(),
            body.receivedAt(),
            ClientIp.from(request)
        );
        return new NotificationResponse(mapper.notification(event));
    }

    @PutMapping("/clock-test")
    public ClockTestResponse startClockTest(
        @Valid @RequestBody ClockTestRequest body,
        HttpServletRequest request
    ) {
        ClockTestRun run = clockTests.start(participantId(request), body.durationMinutes());
        return new ClockTestResponse(mapper.clockTest(run));
    }

    @GetMapping("/clock-test")
    public ClockTestResponse getClockTest(HttpServletRequest request) {
        return new ClockTestResponse(
            clockTests.current(participantId(request)).map(mapper::clockTest).orElse(null)
        );
    }

    @DeleteMapping("/clock-test")
    public ClockTestResponse stopClockTest(HttpServletRequest request) {
        return new ClockTestResponse(
            clockTests.stop(participantId(request)).map(mapper::clockTest).orElse(null)
        );
    }

    private UUID participantId(HttpServletRequest request) {
        AuthenticatedSession authenticated = (AuthenticatedSession) request.getAttribute(
            AuthenticatedSession.REQUEST_ATTRIBUTE
        );
        if (authenticated == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "A valid anonymous session is required.");
        }
        return authenticated.value().participantId();
    }
}

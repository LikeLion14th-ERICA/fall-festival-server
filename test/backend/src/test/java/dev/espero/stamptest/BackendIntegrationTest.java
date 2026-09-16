package dev.espero.stamptest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.stamptest.domain.DomainModels.ClockTestRun;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.push.PushGateway;
import dev.espero.stamptest.service.ClockTestScheduler;
import dev.espero.stamptest.service.ClockTestService;
import dev.espero.stamptest.service.ExpiredParticipantCleanup;
import dev.espero.stamptest.service.PushDeliveryWorker;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BackendIntegrationTest.TestBeans.class)
class BackendIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final Instant BASE_TIME = Instant.parse("2026-05-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired RecordingPushGateway gateway;
    @Autowired ClockTestScheduler scheduler;
    @Autowired ClockTestService clockTests;
    @Autowired ExpiredParticipantCleanup cleanup;
    @Autowired PushDeliveryWorker deliveryWorker;

    @BeforeEach
    void resetState() {
        jdbc.getJdbcTemplate().update("DELETE FROM participants");
        clock.set(BASE_TIME);
        gateway.reset();
    }

    @Test
    void sessionIssuanceIsAccessProtectedValidatedAndReusable() throws Exception {
        mvc.perform(post("/test-api/session").header("Origin", ORIGIN))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.error.code").value("ACCESS_CODE_REQUIRED"));

        mvc.perform(post("/test-api/session")
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("accessCode", "x".repeat(257)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        SessionClient client = openSession();
        assertThat(client.cookie().getName()).isEqualTo("stamp_sid");
        assertThat(client.setCookie()).contains("HttpOnly", "SameSite=Lax", "Path=/");
        assertThat(client.setCookie()).doesNotContain("Secure");

        MvcResult reused = mvc.perform(post("/test-api/session")
                .header("Origin", ORIGIN)
                .cookie(client.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerKey").value(client.ownerKey()))
            .andExpect(jsonPath("$.state.counter.value").value(0))
            .andReturn();
        assertThat(cookieFrom(reused).getValue()).isEqualTo(client.cookie().getValue());
    }

    @Test
    void counterIsBoundedSessionScopedAndIdempotent() throws Exception {
        SessionClient first = openSession();
        UUID operationId = UUID.randomUUID();

        adjust(first, operationId, 1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counter.value").value(1))
            .andExpect(jsonPath("$.counter.version").value(1))
            .andExpect(jsonPath("$.duplicate").value(false));
        adjust(first, operationId, 1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counter.value").value(1))
            .andExpect(jsonPath("$.counter.version").value(1))
            .andExpect(jsonPath("$.duplicate").value(true));

        mvc.perform(post("/test-api/me/counter-operations")
                .header("Origin", ORIGIN)
                .cookie(first.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(counterOperation(UUID.randomUUID(), 1)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));

        mvc.perform(post("/test-api/me/counter-operations")
                .header("Origin", "https://attacker.example")
                .header("X-CSRF-Token", first.csrfToken())
                .cookie(first.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(counterOperation(UUID.randomUUID(), 1)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ORIGIN_NOT_ALLOWED"));

        mvc.perform(post("/test-api/me/counter-operations")
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", first.csrfToken())
                .cookie(first.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(counterOperation(UUID.randomUUID(), 0)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("DELTA_INVALID"));

        for (int index = 0; index < 12; index++) {
            adjust(first, UUID.randomUUID(), 1).andExpect(status().isOk());
        }
        mvc.perform(get("/test-api/me/state").cookie(first.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counter.value").value(10))
            .andExpect(jsonPath("$.counter.version").value(10));

        SessionClient second = openSession();
        mvc.perform(get("/test-api/me/state").cookie(second.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counter.value").value(0))
            .andExpect(jsonPath("$.counter.version").value(0));
    }

    @Test
    void subscriptionUpsertsWithoutEchoingSecretsAndCanBeDeactivated() throws Exception {
        SessionClient client = openSession();
        String body = subscriptionBody("en-US", "Europe/Paris");

        MvcResult created = mutate(post("/test-api/me/push-subscriptions"), client, body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
            .andExpect(jsonPath("$.subscription.locale").value("en"))
            .andExpect(jsonPath("$.subscription.timeZone").value("Europe/Paris"))
            .andExpect(jsonPath("$.subscription.endpoint").doesNotExist())
            .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        String id = createdBody.get("subscription").get("id").asText();

        mutate(post("/test-api/me/push-subscriptions"), client, subscriptionBody("xx", "Asia/Seoul"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscription.id").value(id))
            .andExpect(jsonPath("$.subscription.locale").value("ko"));

        mutate(delete("/test-api/me/push-subscriptions/{id}", id), client, null)
            .andExpect(status().isNoContent());
        mvc.perform(get("/test-api/me/state").cookie(client.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pushSubscriptions[0].status").value("INACTIVE"))
            .andExpect(jsonPath("$.pushSubscriptions[0].deactivationReason").value("USER_UNSUBSCRIBED"));
    }

    @Test
    void immediateNotificationPayloadMatchesServiceWorkerAndCapabilityAckNeedsNoSession() throws Exception {
        SessionClient client = openSession();
        registerSubscription(client, "ko", "Asia/Seoul");

        MvcResult sent = mutate(post("/test-api/me/notifications/test-now"), client, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notification.kind").value("TEST_NOW"))
            .andExpect(jsonPath("$.notification.status").value("ACCEPTED"))
            .andReturn();
        String notificationId = objectMapper.readTree(sent.getResponse().getContentAsString())
            .get("notification").get("id").asText();

        assertThat(gateway.payloads()).hasSize(1);
        JsonNode payload = objectMapper.readTree(gateway.payloads().getFirst());
        assertThat(payload.get("notificationId").asText()).isEqualTo(notificationId);
        assertThat(payload.get("title").asText()).isEqualTo("알림 전송 테스트");
        assertThat(payload.get("body").asText()).contains("09:00:00", "Asia/Seoul");
        assertThat(payload.get("icon").asText()).isEqualTo("/icon-192.png");
        assertThat(payload.get("badge").asText()).isEqualTo("/icon-maskable-512.png");
        assertThat(payload.get("notification").get("title").asText()).isEqualTo(payload.get("title").asText());
        assertThat(payload.has("web_push")).isFalse();

        mvc.perform(post("/test-api/me/notifications/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "notificationId", notificationId,
                    "ackToken", payload.get("ackToken").asText(),
                    "receivedAt", "2026-05-01T09:00:00+09:00"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notification.status").value("ACKNOWLEDGED"));

        mvc.perform(post("/test-api/me/notifications/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "notificationId", notificationId,
                    "ackToken", "invalid",
                    "receivedAt", "2026-05-01T09:00:00+09:00"
                ))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOTIFICATION_ACK_NOT_FOUND"));
    }

    @Test
    void gonePushEndpointIsDeactivatedWithoutRetry() throws Exception {
        SessionClient client = openSession();
        registerSubscription(client, "ko", "Asia/Seoul");
        gateway.status(410);

        mutate(post("/test-api/me/notifications/test-now"), client, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notification.status").value("FAILED"));
        mvc.perform(get("/test-api/me/state").cookie(client.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pushSubscriptions[0].status").value("INACTIVE"))
            .andExpect(jsonPath("$.pushSubscriptions[0].deactivationReason").value("PUSH_SERVICE_410"));
        assertThat(gateway.payloads()).hasSize(1);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
            "SELECT attempt_count FROM push_deliveries", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void retryablePushResponseHonorsRetryAfterAndKeepsOneLogicalMessage() throws Exception {
        SessionClient client = openSession();
        registerSubscription(client, "ko", "Asia/Seoul");
        gateway.result(503, Duration.ofSeconds(10));

        MvcResult initial = mutate(post("/test-api/me/notifications/test-now"), client, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notification.status").value("SENDING"))
            .andReturn();
        String eventId = objectMapper.readTree(initial.getResponse().getContentAsString())
            .get("notification").get("id").asText();
        assertThat(jdbc.getJdbcTemplate().queryForObject(
            "SELECT status FROM push_deliveries", String.class
        )).isEqualTo("RETRY");
        assertThat(jdbc.getJdbcTemplate().queryForObject(
            "SELECT attempt_count FROM push_deliveries", Integer.class
        )).isEqualTo(1);

        clock.advance(Duration.ofSeconds(9));
        gateway.result(201, null);
        deliveryWorker.processDue();
        assertThat(gateway.payloads()).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        deliveryWorker.processDue();
        assertThat(gateway.payloads()).hasSize(2);
        JsonNode firstPayload = objectMapper.readTree(gateway.payloads().get(0));
        JsonNode secondPayload = objectMapper.readTree(gateway.payloads().get(1));
        assertThat(secondPayload.get("notificationId").asText()).isEqualTo(eventId);
        assertThat(secondPayload.get("messageId").asText()).isEqualTo(firstPayload.get("messageId").asText());
        assertThat(secondPayload.get("tag").asText()).isEqualTo(firstPayload.get("tag").asText());
        assertThat(gateway.ttls()).containsExactly(Duration.ofSeconds(70), Duration.ofSeconds(60));
        assertThat(jdbc.getJdbcTemplate().queryForObject(
            "SELECT status FROM notification_events", String.class
        )).isEqualTo("ACCEPTED");
    }

    @Test
    void transferredSubscriptionCannotReceiveThePreviousOwnersQueuedNotification() throws Exception {
        SessionClient previousOwner = openSession();
        registerSubscription(previousOwner, "ko", "Asia/Seoul");
        gateway.result(503, Duration.ofSeconds(10));
        mutate(post("/test-api/me/notifications/test-now"), previousOwner, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notification.status").value("SENDING"));

        SessionClient newOwner = openSession();
        registerSubscription(newOwner, "ko", "Asia/Seoul");
        gateway.result(201, null);
        clock.advance(Duration.ofSeconds(10));
        deliveryWorker.processDue();

        assertThat(gateway.payloads()).hasSize(1);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
            "SELECT status FROM notification_events", String.class
        )).isEqualTo("FAILED");
        assertThat(jdbc.getJdbcTemplate().queryForObject(
            "SELECT error_code FROM push_deliveries", String.class
        )).isEqualTo("SUBSCRIPTION_OWNER_MISMATCH");
    }

    @Test
    void clockTestIsIdempotentAndSendsExactlyFiveUniqueMinuteEvents() throws Exception {
        SessionClient client = openSession();
        registerSubscription(client, "en", "Asia/Seoul");

        MvcResult firstStart = mutate(
            put("/test-api/me/clock-test"), client, "{\"durationMinutes\":5}"
        ).andExpect(status().isOk())
            .andExpect(jsonPath("$.clockTest.status").value("ACTIVE"))
            .andExpect(jsonPath("$.clockTest.sentCount").value(0))
            .andReturn();
        String runId = objectMapper.readTree(firstStart.getResponse().getContentAsString())
            .get("clockTest").get("runId").asText();

        mutate(put("/test-api/me/clock-test"), client, "{\"durationMinutes\":5}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clockTest.runId").value(runId));
        assertThat(gateway.payloads()).isEmpty();

        for (int sequence = 1; sequence <= 5; sequence++) {
            clock.advance(Duration.ofMinutes(1));
            scheduler.tick();
            assertThat(gateway.payloads()).hasSize(sequence);
        }

        mvc.perform(get("/test-api/me/clock-test").cookie(client.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clockTest.status").value("COMPLETED"))
            .andExpect(jsonPath("$.clockTest.sentCount").value(5))
            .andExpect(jsonPath("$.clockTest.failedCount").value(0));

        List<Integer> sequences = jdbc.getJdbcTemplate().queryForList(
            "SELECT sequence FROM notification_events ORDER BY sequence", Integer.class
        );
        List<String> tags = jdbc.getJdbcTemplate().queryForList(
            "SELECT notification_tag FROM notification_events", String.class
        );
        assertThat(sequences).containsExactly(1, 2, 3, 4, 5);
        assertThat(new HashSet<>(tags)).hasSize(5);
        assertThat(gateway.payloads()).allSatisfy(payloadJson -> {
            try {
                JsonNode payload = objectMapper.readTree(payloadJson);
                assertThat(payload.get("tag").asText()).startsWith("clock-" + runId + "-");
                assertThat(payload.get("scheduledAt").asText()).endsWith("+09:00");
                assertThat(payload.get("sentAt").asText()).endsWith("+09:00");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    void overdueClockSlotsAreRecordedMissedWithoutBursting() throws Exception {
        SessionClient client = openSession();
        registerSubscription(client, "ko", "Asia/Seoul");
        mutate(put("/test-api/me/clock-test"), client, "{\"durationMinutes\":5}")
            .andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(3).plusSeconds(30));
        scheduler.tick();

        assertThat(gateway.payloads()).hasSize(1);
        assertThat(jdbc.getJdbcTemplate().queryForList(
            "SELECT sequence FROM notification_events WHERE error_code = 'MISSED_SCHEDULE' ORDER BY sequence",
            Integer.class
        )).containsExactly(1, 2);
        assertThat(jdbc.getJdbcTemplate().queryForList(
            "SELECT sequence FROM notification_events WHERE status = 'ACCEPTED'",
            Integer.class
        )).containsExactly(3);
        mvc.perform(get("/test-api/me/clock-test").cookie(client.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clockTest.status").value("ACTIVE"))
            .andExpect(jsonPath("$.clockTest.sentCount").value(1))
            .andExpect(jsonPath("$.clockTest.failedCount").value(2));
    }

    @Test
    void concurrentClockStartsReturnOneActiveRun() throws Exception {
        SessionClient client = openSession();
        registerSubscription(client, "ko", "Asia/Seoul");
        UUID participantId = jdbc.getJdbcTemplate().queryForObject(
            "SELECT id FROM participants WHERE owner_key = ?", UUID.class, client.ownerKey()
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ClockTestRun>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return clockTests.start(participantId, 5);
                }));
            }
            start.countDown();
            ClockTestRun first = futures.get(0).get();
            ClockTestRun second = futures.get(1).get();
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM clock_test_runs WHERE status = 'ACTIVE'", Integer.class
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredParticipantCleanupCascadesAllOwnedData() throws Exception {
        SessionClient client = openSession();
        adjust(client, UUID.randomUUID(), 1).andExpect(status().isOk());
        registerSubscription(client, "ko", "Asia/Seoul");
        mutate(post("/test-api/me/notifications/test-now"), client, null).andExpect(status().isOk());
        mutate(put("/test-api/me/clock-test"), client, "{\"durationMinutes\":5}").andExpect(status().isOk());
        jdbc.getJdbcTemplate().update(
            "UPDATE participants SET expires_at = ?",
            java.time.OffsetDateTime.ofInstant(clock.instant().minusSeconds(1), ZoneOffset.UTC)
        );

        assertThat(cleanup.deleteExpired()).isEqualTo(1);
        for (String table : List.of(
            "participant_sessions", "counters", "counter_operations", "push_subscriptions",
            "clock_test_runs", "notification_events", "push_deliveries", "participants"
        )) {
            assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class
            )).as(table).isZero();
        }
    }

    private SessionClient openSession() throws Exception {
        MvcResult result = mvc.perform(post("/test-api/session")
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessCode\":\"test-access-code\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.csrfToken").isString())
            .andExpect(jsonPath("$.ownerKey").isString())
            .andExpect(jsonPath("$.state.counter.value").value(0))
            .andExpect(jsonPath("$.state.clockTest").doesNotExist())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new SessionClient(
            cookieFrom(result),
            result.getResponse().getHeader("Set-Cookie"),
            body.get("csrfToken").asText(),
            body.get("ownerKey").asText()
        );
    }

    private org.springframework.test.web.servlet.ResultActions adjust(
        SessionClient client,
        UUID operationId,
        int delta
    ) throws Exception {
        return mutate(
            post("/test-api/me/counter-operations"),
            client,
            counterOperation(operationId, delta)
        );
    }

    private String counterOperation(UUID operationId, int delta) throws Exception {
        return objectMapper.writeValueAsString(Map.of("operationId", operationId, "delta", delta));
    }

    private UUID registerSubscription(SessionClient client, String locale, String timeZone) throws Exception {
        MvcResult result = mutate(
            post("/test-api/me/push-subscriptions"),
            client,
            subscriptionBody(locale, timeZone)
        ).andExpect(status().isOk()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
            .get("subscription").get("id").asText());
    }

    private String subscriptionBody(String locale, String timeZone) throws Exception {
        byte[] publicKey = new byte[65];
        publicKey[0] = 0x04;
        byte[] auth = new byte[16];
        for (int index = 1; index < publicKey.length; index++) {
            publicKey[index] = (byte) index;
        }
        for (int index = 0; index < auth.length; index++) {
            auth[index] = (byte) (index + 1);
        }
        return objectMapper.writeValueAsString(Map.of(
            "endpoint", "https://push.example.test/send/subscription-1",
            "keys", Map.of(
                "p256dh", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey),
                "auth", Base64.getUrlEncoder().withoutPadding().encodeToString(auth)
            ),
            "locale", locale,
            "timeZone", timeZone
        ));
    }

    private org.springframework.test.web.servlet.ResultActions mutate(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
        SessionClient client,
        String body
    ) throws Exception {
        builder.header("Origin", ORIGIN)
            .header("X-CSRF-Token", client.csrfToken())
            .cookie(client.cookie());
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    private Cookie cookieFrom(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        String pair = setCookie.substring(0, setCookie.indexOf(';'));
        String[] parts = pair.split("=", 2);
        return new Cookie(parts[0], parts[1]);
    }

    private record SessionClient(Cookie cookie, String setCookie, String csrfToken, String ownerKey) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_TIME);
        }

        @Bean
        @Primary
        RecordingPushGateway recordingPushGateway() {
            return new RecordingPushGateway();
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    static final class RecordingPushGateway implements PushGateway {

        private final CopyOnWriteArrayList<String> payloads = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Duration> ttls = new CopyOnWriteArrayList<>();
        private final AtomicInteger status = new AtomicInteger(201);
        private final AtomicReference<Duration> retryAfter = new AtomicReference<>();

        @Override
        public PushResult send(PushSubscription subscription, String payload, Duration ttl) {
            payloads.add(payload);
            ttls.add(ttl);
            return new PushResult(status.get(), retryAfter.get());
        }

        void status(int value) {
            status.set(value);
        }

        void result(int statusCode, Duration delay) {
            status.set(statusCode);
            retryAfter.set(delay);
        }

        List<String> payloads() {
            return List.copyOf(payloads);
        }

        List<Duration> ttls() {
            return List.copyOf(ttls);
        }

        void reset() {
            payloads.clear();
            ttls.clear();
            status.set(201);
            retryAfter.set(null);
        }
    }
}

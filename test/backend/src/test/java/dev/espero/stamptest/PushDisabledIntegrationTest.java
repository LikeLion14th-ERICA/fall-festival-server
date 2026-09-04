package dev.espero.stamptest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.NotificationKind;
import dev.espero.stamptest.persistence.PushSubscriptionStore;
import dev.espero.stamptest.persistence.SessionStore;
import dev.espero.stamptest.push.PushGateway;
import dev.espero.stamptest.service.NotificationCreationService;
import dev.espero.stamptest.service.PushDeliveryWorker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "app.push.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:push-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE"
})
@ActiveProfiles("test")
@Import(PushDisabledIntegrationTest.TestBeans.class)
class PushDisabledIntegrationTest {

    @Autowired SessionStore sessions;
    @Autowired PushSubscriptionStore subscriptions;
    @Autowired NotificationCreationService creation;
    @Autowired PushDeliveryWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired NeverCalledGateway gateway;
    @Autowired Clock clock;

    @Test
    void disabledPushFailsOnceWithoutCallingTheGatewayOrLeavingRetryWork() {
        Instant now = clock.instant();
        UUID participantId = UUID.randomUUID();
        sessions.create(
            participantId,
            UUID.randomUUID(),
            "owner-" + UUID.randomUUID(),
            "a".repeat(64),
            "csrf-token",
            now,
            now.plus(Duration.ofDays(7))
        );
        subscriptions.upsert(
            participantId,
            "https://push.example.test/id",
            "b".repeat(64),
            "p256dh",
            "auth",
            null,
            "ko",
            "Asia/Seoul",
            now
        );
        NotificationEvent event = creation.create(
            participantId, null, NotificationKind.TEST_NOW, null, now
        );

        worker.processEvent(event.id());
        worker.processDue();

        assertThat(gateway.calls()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_events", String.class))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM push_deliveries", String.class))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM push_deliveries", String.class))
            .isEqualTo("PUSH_NOT_CONFIGURED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM push_deliveries", Integer.class))
            .isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        NeverCalledGateway neverCalledGateway() {
            return new NeverCalledGateway();
        }
    }

    static final class NeverCalledGateway implements PushGateway {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public PushResult send(
            dev.espero.stamptest.domain.DomainModels.PushSubscription subscription,
            String payload,
            Duration ttl
        ) {
            calls.incrementAndGet();
            return new PushResult(201, null);
        }

        int calls() {
            return calls.get();
        }
    }
}

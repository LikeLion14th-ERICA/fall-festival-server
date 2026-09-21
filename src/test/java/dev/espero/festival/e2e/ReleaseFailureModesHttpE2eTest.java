package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises release failure paths on the real HTTP boundary. The database is
 * deliberately points at a festival without a published catalog: a deploy
 * must stay diagnosable, bounded, and safe before an operator publishes content.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "festival.id=114018bd-c37b-4454-8dd7-9e4422928253",
        "server.address=127.0.0.1",
        "spring.flyway.enabled=false",
        "festival.admin-auth.jwt-signing-secret=backend-e2e-test-signing-secret-32-bytes",
        "festival.admin-auth.allowed-origin=https://admin.e2e.test",
        "festival.admin-auth.bootstrap-username=release-e2e-admin",
        "festival.admin-auth.bootstrap-password=release-e2e-password",
        "festival.rate-limit.enabled=true",
        "festival.rate-limit.trusted-proxy-hops=1",
        "festival.rate-limit.public-read.capacity=1",
        "festival.rate-limit.public-read.refill-per-second=1.0",
        "festival.rate-limit.admin-login.capacity=1",
        "festival.rate-limit.admin-login.refill-per-second=1.0",
        "festival.cleanup.schedule-enabled=false",
        "festival.cleanup.dry-run=true",
        "festival.cleanup.datasource.url=",
        "festival.cleanup.datasource.username=",
        "festival.cleanup.datasource.password=",
        "festival.cleanup.datasource.role=",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
            + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration"
    }
)
@ActiveProfiles("db")
@Import(ReleaseFailureModesHttpE2eTest.ClockConfiguration.class)
@Testcontainers
class ReleaseFailureModesHttpE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("114018bd-c37b-4454-8dd7-9e4422928253");
    private static final String ADMIN_ORIGIN = "https://admin.e2e.test";
    private static final String ADMIN_USERNAME = "release-e2e-admin";
    private static final String ADMIN_PASSWORD = "release-e2e-password";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Object MIGRATION_LOCK = new Object();
    private static volatile boolean migrated;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        migrateEmptyDatabase();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.hikari.username", POSTGRES::getUsername);
        registry.add("spring.datasource.hikari.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.festivalId", FESTIVAL_ID::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Test
    @Timeout(60)
    void unpublishedCatalogStaysUnavailableWhileRateLimitsAreScopedAndRecoverable() throws Exception {
        HttpResponse<String> health = send(request("/healthz").GET().build());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"ok\"}");

        HttpResponse<String> readiness = send(request("/readyz").GET().build());
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"not_ready\"}");

        HttpResponse<String> firstVisitor = publicConfig("198.51.100.10", "untrusted-client-request-id");
        assertCatalogNotReady(firstVisitor, "untrusted-client-request-id");

        HttpResponse<String> limitedVisitor = publicConfig("198.51.100.10", "second-client-request-id");
        assertRateLimited(limitedVisitor, "second-client-request-id");

        HttpResponse<String> independentVisitor = publicConfig("198.51.100.11", "other-client-request-id");
        assertCatalogNotReady(independentVisitor, "other-client-request-id");

        clock.advance(Duration.ofSeconds(1));
        HttpResponse<String> recoveredVisitor = publicConfig("198.51.100.10", "recovered-client-request-id");
        assertCatalogNotReady(recoveredVisitor, "recovered-client-request-id");

        HttpResponse<String> failedLogin = login("203.0.113.10", "wrong-password");
        assertThat(failedLogin.statusCode()).isEqualTo(401);
        assertThat(errorCode(failedLogin)).isEqualTo("ADMIN_AUTHENTICATION_FAILED");
        assertThat(failedLogin.headers().allValues("Set-Cookie")).isEmpty();

        HttpResponse<String> limitedLogin = login("203.0.113.10", "wrong-password");
        assertRateLimited(limitedLogin, null);
        assertThat(limitedLogin.headers().allValues("Set-Cookie")).isEmpty();

        HttpResponse<String> independentLogin = login("203.0.113.11", "wrong-password");
        assertThat(independentLogin.statusCode()).isEqualTo(401);
        assertThat(errorCode(independentLogin)).isEqualTo("ADMIN_AUTHENTICATION_FAILED");

        clock.advance(Duration.ofSeconds(1));
        HttpResponse<String> recoveredLogin = login("203.0.113.10", "wrong-password");
        assertThat(recoveredLogin.statusCode()).isEqualTo(401);
        assertThat(errorCode(recoveredLogin)).isEqualTo("ADMIN_AUTHENTICATION_FAILED");
    }

    private HttpResponse<String> publicConfig(String forwardedFor, String requestId) throws Exception {
        return send(request("/api/v2/config")
            .header("X-Forwarded-For", forwardedFor)
            .header("X-Request-Id", requestId)
            .GET()
            .build());
    }

    private HttpResponse<String> login(String forwardedFor, String password) throws Exception {
        return send(request("/api/v2/admin/sessions")
            .header("Origin", ADMIN_ORIGIN)
            .header("X-Forwarded-For", forwardedFor)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + ADMIN_USERNAME + "\",\"password\":\"" + password + "\"}"
            ))
            .build());
    }

    private void assertCatalogNotReady(HttpResponse<String> response, String clientRequestId) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(errorCode(response)).isEqualTo("CATALOG_NOT_READY");
        assertThat(JsonPath.<Boolean>read(response.body(), "$.error.retryable")).isTrue();
        assertThat(JsonPath.<Number>read(response.body(), "$.meta.revision").longValue()).isZero();
        assertServerRequestId(response, clientRequestId);
    }

    private void assertRateLimited(HttpResponse<String> response, String clientRequestId) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(errorCode(response)).isEqualTo("RATE_LIMITED");
        assertThat(JsonPath.<Boolean>read(response.body(), "$.error.retryable")).isTrue();
        assertThat(requiredHeader(response, "Retry-After")).isEqualTo("1");
        assertServerRequestId(response, clientRequestId);
    }

    private void assertServerRequestId(HttpResponse<String> response, String clientRequestId) {
        String generated = requiredHeader(response, "X-Request-Id");
        assertThat(generated).matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        if (clientRequestId != null) {
            assertThat(generated).isNotEqualTo(clientRequestId);
        }
        assertThat(JsonPath.<String>read(response.body(), "$.meta.requestId")).isEqualTo(generated);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String errorCode(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.error.code");
    }

    private String requiredHeader(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow(() -> new AssertionError(
            "Expected " + name + " on HTTP " + response.statusCode()
        ));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(REQUEST_TIMEOUT);
    }

    private static void migrateEmptyDatabase() {
        if (migrated) {
            return;
        }
        synchronized (MIGRATION_LOCK) {
            if (migrated) {
                return;
            }
            POSTGRES.start();
            Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
                .load()
                .migrate();
            migrated = true;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock releaseFailureModesClock() {
            return new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        }

        @Bean(name = "dataSource")
        @Primary
        DataSource releaseFailureModesDataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

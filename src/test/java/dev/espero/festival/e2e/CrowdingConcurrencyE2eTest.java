package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogCliRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the administrator crowding write through a real loopback HTTP
 * server while two clients race on one representation and idempotency key.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "server.address=127.0.0.1",
        "spring.flyway.enabled=false",
        "festival.admin-auth.jwt-signing-secret=backend-e2e-test-signing-secret-32-bytes",
        "festival.admin-auth.allowed-origin=https://admin.e2e.test",
        "festival.admin-auth.bootstrap-username=release-e2e-admin",
        "festival.admin-auth.bootstrap-password=release-e2e-password"
    }
)
@ActiveProfiles("db")
@Import(CrowdingConcurrencyE2eTest.ClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CrowdingConcurrencyE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String ADMIN_ORIGIN = "https://admin.e2e.test";
    private static final String ADMIN_USERNAME = "release-e2e-admin";
    private static final String ADMIN_PASSWORD = "release-e2e-password";
    private static final String REFRESH_COOKIE_NAME = "__Host-festival-admin-refresh";
    private static final UUID INITIAL_REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FUTURE_TIMEOUT = Duration.ofSeconds(15);
    private static final Path CANDIDATE_MANIFEST = Path.of("dev", "catalog", "frontend-mock-catalog.json");
    private static final Object PREPARATION_LOCK = new Object();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static volatile PreparedCandidate preparedCandidate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        prepareCandidateDatabase();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.hikari.username", POSTGRES::getUsername);
        registry.add("spring.datasource.hikari.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.data-source-class-name", () -> "org.postgresql.ds.PGSimpleDataSource");
        registry.add("spring.datasource.hikari.data-source-properties.URL", () -> "jdbc:unsupported:crowding-http");
        registry.add("spring.config.import", () -> "");
        registry.add(
            "spring.autoconfigure.exclude",
            () -> "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration"
        );
    }

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private MutableClock clock;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Test
    void concurrentIdenticalCrowdingWritesMutateOnceReplaySafelyAndRejectKeyReuse() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        String accessToken = login();
        HttpResponse<String> initial = adminCrowding(accessToken);
        assertThat(initial.statusCode()).isEqualTo(200);
        String initialEtag = requiredHeader(initial, "ETag");
        assertThat(JsonPath.<String>read(initial.body(), "$.data.status")).isEqualTo("RELAXED");

        String idempotencyKey = "crowding-concurrency-e2e-1";
        HttpRequest first = updateRequest(accessToken, initialEtag, idempotencyKey, "CROWDED");
        HttpRequest second = updateRequest(accessToken, initialEtag, idempotencyKey, "CROWDED");
        List<HttpResponse<String>> concurrent = sendTogether(first, second);

        assertThat(concurrent).extracting(HttpResponse::statusCode).allMatch(status -> status == 204 || status == 409);
        assertThat(concurrent).extracting(HttpResponse::statusCode).contains(204);
        concurrent.stream()
            .filter(response -> response.statusCode() == 409)
            .forEach(response -> assertThat(errorCode(response)).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
        concurrent.stream()
            .filter(response -> response.statusCode() == 204)
            .forEach(response -> assertThat(response.body()).isEmpty());

        assertThat(crowdingAuditCount()).isOne();
        assertThat(crowdingStateCount(candidate.operatingDay())).isOne();
        assertThat(crowdingState(candidate.operatingDay())).isEqualTo("CROWDED");

        HttpResponse<String> replay = send(first);
        assertThat(replay.statusCode()).isEqualTo(204);
        assertThat(replay.body()).isEmpty();
        assertThat(crowdingAuditCount()).isOne();

        HttpResponse<String> current = adminCrowding(accessToken);
        String currentEtag = requiredHeader(current, "ETag");
        HttpResponse<String> reused = send(updateRequest(accessToken, currentEtag, idempotencyKey, "MODERATE"));
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(JsonPath.<Boolean>read(reused.body(), "$.error.retryable")).isFalse();
        assertThat(crowdingAuditCount()).isOne();
        assertThat(crowdingState(candidate.operatingDay())).isEqualTo("CROWDED");
    }

    private String login() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .header("Origin", ADMIN_ORIGIN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + ADMIN_USERNAME + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"
            ))
            .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(requiredHeader(response, "Access-Control-Allow-Origin")).isEqualTo(ADMIN_ORIGIN);
        assertThat(requiredHeader(response, "Set-Cookie")).contains(REFRESH_COOKIE_NAME + "=", "HttpOnly", "Secure");
        return JsonPath.read(response.body(), "$.data.accessToken");
    }

    private HttpResponse<String> adminCrowding(String accessToken) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + accessToken)
            .header("Origin", ADMIN_ORIGIN)
            .GET()
            .build());
    }

    private HttpRequest updateRequest(String accessToken, String etag, String key, String level) {
        return HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + accessToken)
            .header("Origin", ADMIN_ORIGIN)
            .header("Content-Type", "application/json")
            .header("If-Match", etag)
            .header("Idempotency-Key", key)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"level\":\"" + level + "\"}"))
            .build();
    }

    private List<HttpResponse<String>> sendTogether(HttpRequest first, HttpRequest second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        Future<HttpResponse<String>> firstResult = null;
        Future<HttpResponse<String>> secondResult = null;
        try {
            firstResult = executor.submit(() -> sendWhenReleased(first, ready, start));
            secondResult = executor.submit(() -> sendWhenReleased(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(await(firstResult), await(secondResult));
        } catch (Exception | AssertionError failure) {
            if (firstResult != null) {
                firstResult.cancel(true);
            }
            if (secondResult != null) {
                secondResult.cancel(true);
            }
            throw failure;
        } finally {
            executor.shutdownNow();
        }
    }

    private HttpResponse<String> sendWhenReleased(
        HttpRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return send(request);
    }

    private HttpResponse<String> await(Future<HttpResponse<String>> future) throws Exception {
        try {
            return future.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        } catch (InterruptedException | TimeoutException failure) {
            future.cancel(true);
            throw failure;
        }
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

    private long crowdingAuditCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE action = 'CROWDING_UPDATED'",
            Long.class
        );
    }

    private long crowdingStateCount(LocalDate operatingDay) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM crowding_state_dynamic WHERE festival_id = ? AND operating_date = ?",
            Long.class, FESTIVAL_ID, operatingDay
        );
    }

    private String crowdingState(LocalDate operatingDay) {
        return jdbc.queryForObject(
            "SELECT level FROM crowding_state_dynamic WHERE festival_id = ? AND operating_date = ?",
            String.class, FESTIVAL_ID, operatingDay
        );
    }

    private static PreparedCandidate prepared() {
        PreparedCandidate candidate = preparedCandidate;
        if (candidate == null) {
            throw new IllegalStateException("Candidate preparation did not complete.");
        }
        return candidate;
    }

    private static void prepareCandidateDatabase() {
        if (preparedCandidate != null) {
            return;
        }
        synchronized (PREPARATION_LOCK) {
            if (preparedCandidate != null) {
                return;
            }
            try {
                POSTGRES.start();
                migrate();
                UUID baseline = publishedRevision();
                UUID revision = importAndPublishWithHostileOverrides(baseline);
                preparedCandidate = new PreparedCandidate(
                    revision,
                    midpointOfFirstOperatingWindow(revision)
                );
            } catch (Exception exception) {
                throw new IllegalStateException("Could not prepare the published crowding candidate.", exception);
            }
        }
    }

    private static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
            .load()
            .migrate();
    }

    private static UUID importAndPublish(UUID baseline) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CatalogCliApplication.class)
            .environment(containerEnvironment())
            .profiles("db", "catalog-cli")
            .web(WebApplicationType.NONE)
            .run()) {
            CatalogCliRunner runner = context.getBean(CatalogCliRunner.class);
            runner.run(new DefaultApplicationArguments(
                "import",
                "--manifest=" + CANDIDATE_MANIFEST.toAbsolutePath(),
                "--festival-id=" + FESTIVAL_ID,
                "--baseline-revision=" + baseline,
                "--actor=crowding-concurrency-e2e"
            ));
            UUID draft = newestDraftRevision();
            runner.run(new DefaultApplicationArguments(
                "publish",
                "--revision=" + draft,
                "--actor=crowding-concurrency-e2e"
            ));
            assertThat(publishedRevision()).isEqualTo(draft);
            return draft;
        }
    }

    private static UUID importAndPublishWithHostileOverrides(UUID baseline) {
        String previousHikariUrl = System.getProperty("spring.datasource.hikari.jdbc-url");
        String previousDataSourceClass = System.getProperty("spring.datasource.hikari.data-source-class-name");
        String previousDataSourcePropertyUrl = System.getProperty("spring.datasource.hikari.data-source-properties.URL");
        String previousJndiName = System.getProperty("spring.datasource.jndi-name");
        System.setProperty("spring.datasource.hikari.jdbc-url", "jdbc:unsupported:crowding-e2e");
        System.setProperty("spring.datasource.hikari.data-source-class-name", "org.postgresql.ds.PGSimpleDataSource");
        System.setProperty("spring.datasource.hikari.data-source-properties.URL", "jdbc:unsupported:crowding-e2e");
        System.setProperty("spring.datasource.jndi-name", "java:comp/env/jdbc/unsupported-crowding-e2e");
        try {
            return importAndPublish(baseline);
        } finally {
            restoreSystemProperty("spring.datasource.hikari.jdbc-url", previousHikariUrl);
            restoreSystemProperty("spring.datasource.hikari.data-source-class-name", previousDataSourceClass);
            restoreSystemProperty("spring.datasource.hikari.data-source-properties.URL", previousDataSourcePropertyUrl);
            restoreSystemProperty("spring.datasource.jndi-name", previousJndiName);
        }
    }

    private static StandardEnvironment containerEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        // Keep shell/JVM datasource, Hikari, JNDI, profile, and config-import overrides out of the CLI.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("crowding-e2e", Map.ofEntries(
            Map.entry("spring.config.location", "classpath:/application.yml"),
            Map.entry("spring.config.import", ""),
            Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
            Map.entry("spring.datasource.username", POSTGRES.getUsername()),
            Map.entry("spring.datasource.password", POSTGRES.getPassword()),
            Map.entry("spring.datasource.hikari.jdbc-url", POSTGRES.getJdbcUrl()),
            Map.entry("spring.datasource.hikari.username", POSTGRES.getUsername()),
            Map.entry("spring.datasource.hikari.password", POSTGRES.getPassword()),
            Map.entry("spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration"),
            Map.entry("spring.flyway.enabled", false),
            Map.entry("spring.main.banner-mode", "off"),
            Map.entry("festival.id", FESTIVAL_ID.toString())
        )));
        return environment;
    }

    private static void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    private static UUID publishedRevision() {
        return querySingleUuid("""
            SELECT id
            FROM festival_revisions
            WHERE festival_id = ? AND state = 'published'
            """);
    }

    private static UUID newestDraftRevision() {
        return querySingleUuid("""
            SELECT id
            FROM festival_revisions
            WHERE festival_id = ? AND state = 'draft'
            ORDER BY revision_number DESC
            LIMIT 1
            """);
    }

    private static UUID querySingleUuid(String sql) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, FESTIVAL_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                UUID value = rows.getObject(1, UUID.class);
                assertThat(rows.next()).isFalse();
                return value;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read candidate revision.", exception);
        }
    }

    private static Instant midpointOfFirstOperatingWindow(UUID revisionId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT festival_date, opens_at, closes_at
            FROM festival_days
            WHERE festival_revision_id = ?
            ORDER BY festival_date
            LIMIT 1
            """)) {
            statement.setObject(1, revisionId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                OffsetDateTime opensAt = rows.getObject("opens_at", OffsetDateTime.class);
                OffsetDateTime closesAt = rows.getObject("closes_at", OffsetDateTime.class);
                return opensAt.toInstant().plus(Duration.between(opensAt, closesAt).dividedBy(2));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read candidate operating window.", exception);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record PreparedCandidate(UUID revisionId, Instant verificationInstant) {
        LocalDate operatingDay() {
            return verificationInstant.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean(name = "dataSource")
        @Primary
        HikariDataSource crowdingContainerDataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }

        @Bean
        @Primary
        MutableClock crowdingE2eClock() {
            return new MutableClock(prepared().verificationInstant());
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant value) {
            this.instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
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

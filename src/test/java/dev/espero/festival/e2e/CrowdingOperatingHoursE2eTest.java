package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogCliRunner;
import dev.espero.festival.support.PostgresTestImages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, concurrency and polling load against disposable PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "spring.flyway.enabled=false",
    "festival.admin-auth.jwt-signing-secret=hours-http-e2e-test-signing-secret-32-bytes",
    "festival.admin-auth.allowed-origin=https://admin.hours.test",
    "festival.admin-auth.bootstrap-username=hours-e2e-admin",
    "festival.admin-auth.bootstrap-password=hours-e2e-test-password",
    "festival.rate-limit.enabled=false", "festival.public-locales=ko,en",
    "festival.cleanup.schedule-enabled=false", "festival.cleanup.dry-run=true",
    "festival.cleanup.datasource.url=", "festival.cleanup.datasource.username=",
    "festival.cleanup.datasource.password=", "festival.cleanup.datasource.role=",
    "spring.config.import=",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration"
})
@ActiveProfiles("db")
@Import(CrowdingOperatingHoursE2eTest.Configuration.class)
@Testcontainers
@Timeout(180)
class CrowdingOperatingHoursE2eTest {

    private static final UUID FESTIVAL = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MutableClock CLOCK = new MutableClock();
    private static volatile boolean prepared;
    private static UUID initialRevision;
    @Autowired private dev.espero.festival.CatalogRevisionService revisions;
    @Autowired private dev.espero.festival.persistence.CrowdingStore crowding;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        prepareCandidate();
        registry.add("festival.id", FESTIVAL::toString);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpClient http;

    @BeforeEach
    void reset() throws Exception {
        CLOCK.now = Instant.parse("2026-09-29T03:00:00Z");
        jdbc.update("DELETE FROM crowding_operating_hours WHERE festival_id = ?", FESTIVAL);
        jdbc.update("DELETE FROM crowding_state_dynamic WHERE festival_id = ?", FESTIVAL);
        jdbc.update("DELETE FROM admin_idempotency_records");
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("UPDATE festival_revisions SET state = 'archived' WHERE festival_id = ? AND state = 'published' AND id <> ?", FESTIVAL, initialRevision);
        jdbc.update("UPDATE festival_revisions SET state = 'published' WHERE id = ?", initialRevision);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        assertThat(get("/readyz").statusCode()).isEqualTo(200);
    }

    @AfterEach
    void closeClient() {
        http.close();
    }


    private static final String BASE = "/api/v2/admin/crowding/operating-hours";
    private static final String DAY = "2026-09-29";
    private static final String ITEM = BASE + "/" + DAY;

    @Test
    void firstHoursSavesConflictOnce() throws Exception {
        String token = login();
        String etag = tag(adminGet(ITEM, token));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<HttpResponse<String>> first = pool.submit(() -> {
                ready.countDown(); go.await(); return send(hoursRequest(token, etag, key(), "13:00:00", "23:00:00"));
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                ready.countDown(); go.await(); return send(hoursRequest(token, etag, key(), "14:00:00", "23:00:00"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<HttpResponse<String>> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(204, 409);
            results.stream().filter(result -> result.statusCode() == 409)
                .forEach(result -> assertError(result, "EDIT_CONFLICT"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crowding_operating_hours WHERE festival_id = ?", Long.class, FESTIVAL)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = 'CROWDING_OPERATING_HOURS_UPDATED'", Long.class)).isOne();
    }

    @Test
    void concurrentHoursAndLevelWritesUseCurrentSchedule() throws Exception {
        String token = login();
        String initialHours = tag(adminGet(ITEM, token));
        String initialLevel = tag(adminGet("/api/v2/admin/crowding", token));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> pending = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    crowding.lockFestival(FESTIVAL);
                    Future<HttpResponse<String>> result = pool.submit(() -> send(levelRequest(token, initialLevel)));
                    awaitFestivalWait();
                    crowding.saveOperatingHours(FESTIVAL, LocalDate.parse(DAY),
                        java.time.OffsetDateTime.parse(DAY + "T11:00:00+09:00"),
                        java.time.OffsetDateTime.parse(DAY + "T12:00:00+09:00"), CLOCK.now);
                    return result;
                });
            HttpResponse<String> conflict = pending.get(10, TimeUnit.SECONDS);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertError(conflict, "EDIT_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crowding_state_dynamic WHERE festival_id = ?", Long.class, FESTIVAL)).isZero();
            // The stale hours representation also fails after that transaction.
            HttpResponse<String> stale = send(hoursRequest(token, initialHours, key(), "11:00:00", "23:00:00"));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertError(stale, "EDIT_CONFLICT");
        }
    }

    @Test
    void levelWaitingAcrossMidnightRequiresRefetch() throws Exception {
        CLOCK.now = Instant.parse("2026-09-29T14:59:59Z");
        String token = login();
        String tag = tag(adminGet("/api/v2/admin/crowding", token));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> pending = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    crowding.lockFestival(FESTIVAL);
                    Future<HttpResponse<String>> result = pool.submit(() -> send(levelRequest(token, tag)));
                    awaitFestivalWait();
                    CLOCK.now = Instant.parse("2026-09-29T15:00:00Z");
                    return result;
                });
            HttpResponse<String> result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(409);
            assertError(result, "EDIT_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crowding_state_dynamic WHERE festival_id = ?", Long.class, FESTIVAL)).isZero();
        }
    }

    @Test
    void hoursWaitingForPublicationChecksNewMembership() throws Exception {
        String token = login();
        String tag = tag(adminGet(BASE + "/2026-09-30", token));
        Path manifest = Files.createTempFile(Path.of("target"), "hours-removed-day-", ".json");
        String original = Files.readString(Path.of("dev/catalog/development-catalog.json"));
        Files.writeString(manifest, original.replaceFirst("(?s)\\{\\s*\"festivalDate\": \"2026-09-30\".*?\\},\\s*", ""));
        UUID draft = revisions.importManifest(manifest, "hours-e2e", FESTIVAL,
            new dev.espero.festival.CatalogRevisionService.BaselineOverride(initialRevision));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> pending = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    crowding.lockFestival(FESTIVAL);
                    Future<HttpResponse<String>> result = pool.submit(() -> send(admin(BASE + "/2026-09-30", token).header("If-Match", tag)
                        .header("Idempotency-Key", key()).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"opensAt\":\"2026-09-30T11:00:00+09:00\",\"closesAt\":\"2026-09-30T23:00:00+09:00\"}")).build()));
                    awaitFestivalWait();
                    revisions.publish(draft, "hours-e2e");
                    return result;
                });
            HttpResponse<String> result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(409);
            assertError(result, "NOT_FESTIVAL_DAY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crowding_operating_hours WHERE festival_id = ?", Long.class, FESTIVAL)).isZero();
        }
    }

    @Test
    void freshApplicationReadsPersistedHoursWithoutCatalogRewrite() throws Exception {
        String token = login();
        assertThat(send(hoursRequest(token, tag(adminGet(ITEM, token)), key(), "10:00:00", "22:00:00"))
            .statusCode()).isEqualTo(204);
        String persistedTag = tag(adminGet(ITEM, token));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        for (String property : CrowdingOperatingHoursE2eTest.class.getAnnotation(SpringBootTest.class).properties()) {
            int separator = property.indexOf('=');
            settings.put(property.substring(0, separator), property.substring(separator + 1));
        }
        settings.put("festival.id", FESTIVAL.toString());
        settings.put("server.port", 0);
        settings.put("spring.config.location", "classpath:/application.yml");
        environment.getPropertySources().addFirst(new MapPropertySource("hours-restarted-server", settings));
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(
            dev.espero.festival.FallFestivalServerApplication.class, Configuration.class)
            .environment(environment).profiles("db").web(WebApplicationType.SERVLET).run()) {
            String endpoint = "http://127.0.0.1:" + restarted.getEnvironment().getProperty("local.server.port");
            HttpResponse<String> result = send(HttpRequest.newBuilder(URI.create(endpoint + ITEM))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(tag(result)).isEqualTo(persistedTag);
            assertThat(JsonPath.<String>read(result.body(), "$.data.opensAt"))
                .isEqualTo("2026-09-29T10:00:00+09:00");
        }
    }

    /** Existing home polling mix: crowding 34 RPS + ticket guide 33 RPS. */
    @Test
    void pollingLoadSeesHoursUpdatesAndKeepsSavedState() throws Exception {
        String token = login();
        assertThat(send(levelRequest(token, tag(adminGet("/api/v2/admin/crowding", token)))).statusCode()).isEqualTo(204);
        var state = jdbc.queryForMap("SELECT level, updated_at FROM crowding_state_dynamic WHERE festival_id = ?", FESTIVAL);
        for (int i = 0; i < 10; i++) get("/api/v2/crowding");
        long start = System.nanoTime();
        long deadline = start + Duration.ofSeconds(6).toNanos();
        var errors = new ConcurrentLinkedQueue<String>();
        var crowdNanos = new ConcurrentLinkedQueue<Long>();
        var ticketNanos = new ConcurrentLinkedQueue<Long>();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger open = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> crowd = pool.submit(() -> poll("/api/v2/crowding", 34, start, deadline, crowdNanos, errors, closed, open));
            Future<?> ticket = pool.submit(() -> poll("/api/v2/ticket-guide", 33, start, deadline, ticketNanos, errors, closed, open));
            Thread.sleep(1500);
            assertThat(send(hoursRequest(token, tag(adminGet(ITEM, token)), key(), "11:00:00", "12:00:00")).statusCode()).isEqualTo(204);
            Thread.sleep(2000);
            assertThat(send(hoursRequest(token, tag(adminGet(ITEM, token)), key(), "11:00:00", "23:00:00")).statusCode()).isEqualTo(204);
            crowd.get(15, TimeUnit.SECONDS); ticket.get(15, TimeUnit.SECONDS);
        }
        Map<String, Object> report = Map.of("profile", "public-polling-67-rps", "durationSeconds", 6,
            "crowding", metrics(crowdNanos), "ticketGuide", metrics(ticketNanos), "errors", List.copyOf(errors),
            "observedClosed", closed.get(), "observedCrowded", open.get());
        Files.writeString(Path.of("target/crowding-hours-load-report.json"), JSON.writeValueAsString(report));
        assertThat(errors).isEmpty();
        assertThat(closed.get()).isPositive();
        assertThat(open.get()).isPositive();
        assertThat(crowdNanos.size()).isGreaterThan(180);
        assertThat(ticketNanos.size()).isGreaterThan(170);
        for (var samples : List.of(crowdNanos, ticketNanos)) {
            assertThat(percentile(samples, 0.95)).isLessThan(300);
            assertThat(percentile(samples, 0.99)).isLessThan(1000);
        }
        assertThat(jdbc.queryForMap("SELECT level, updated_at FROM crowding_state_dynamic WHERE festival_id = ?", FESTIVAL)).isEqualTo(state);
        assertThat(JsonPath.<String>read(get("/api/v2/crowding").body(), "$.data.status")).isEqualTo("CROWDED");
    }

    private void poll(String route, int rps, long start, long end, ConcurrentLinkedQueue<Long> samples,
        ConcurrentLinkedQueue<String> errors, AtomicInteger closed, AtomicInteger open) {
        long interval = 1_000_000_000L / rps;
        for (long scheduled = start; scheduled < end; scheduled += interval) {
            try {
                long remaining = scheduled - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                long before = System.nanoTime();
                HttpResponse<String> result = get(route);
                samples.add(System.nanoTime() - before);
                if (result.statusCode() != 200) errors.add(route + ": HTTP " + result.statusCode());
                else if (route.endsWith("crowding")) {
                    String status = JsonPath.read(result.body(), "$.data.status");
                    if (status.equals("CLOSED")) closed.incrementAndGet();
                    else if (status.equals("CROWDED")) open.incrementAndGet();
                    else errors.add("Unexpected crowding status: " + status);
                }
            } catch (Exception exception) {
                errors.add(route + ": " + exception.getClass().getSimpleName());
            }
        }
    }

    private Map<String, Object> metrics(ConcurrentLinkedQueue<Long> samples) {
        return Map.of("requests", samples.size(), "p95Ms", percentile(samples, .95), "p99Ms", percentile(samples, .99));
    }
    private double percentile(ConcurrentLinkedQueue<Long> samples, double quantile) {
        List<Long> sorted = samples.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size()-1, (int) Math.ceil(sorted.size()*quantile)-1)) / 1_000_000.0;
    }
    private void awaitFestivalWait() {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < end) {
            // This monitor participates in the lock-holder transaction. Clear
            // its statistics snapshot so the next poll can observe a new waiter.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE '%FROM festivals%'", Long.class) > 0) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("The HTTP mutation never reached the festival lock");
    }
    private String login() throws Exception {
        HttpResponse<String> result = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions"))
            .header("Origin", "https://admin.hours.test").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"hours-e2e-admin\",\"password\":\"hours-e2e-test-password\"}")).build());
        assertThat(result.statusCode()).isEqualTo(200);
        return JsonPath.read(result.body(), "$.data.accessToken");
    }
    private HttpResponse<String> adminGet(String path, String token) throws Exception {
        return send(admin(path, token).GET().build());
    }
    private HttpRequest.Builder admin(String path, String token) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token).header("Origin", "https://admin.hours.test");
    }
    private HttpRequest hoursRequest(String token, String tag, String key, String openTime, String closeTime) {
        return admin(ITEM, token).header("If-Match", tag).header("Idempotency-Key", key).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"opensAt\":\"" + DAY + "T" + openTime + "+09:00\",\"closesAt\":\"" + DAY + "T" + closeTime + "+09:00\"}")).build();
    }
    private HttpRequest levelRequest(String token, String tag) {
        return admin("/api/v2/admin/crowding", token).header("If-Match", tag).header("Idempotency-Key", key())
            .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"level\":\"CROWDED\"}")).build();
    }
    private String key() { return UUID.randomUUID().toString(); }
    private String tag(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("ETag").orElseThrow();
    }
    private void assertError(HttpResponse<String> result, String code) {
        assertThat(JsonPath.<String>read(result.body(), "$.error.code")).isEqualTo(code);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build());
    }
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static synchronized void prepareCandidate() throws Exception {
        if (prepared) return;
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .placeholders(Map.of("festivalId", FESTIVAL.toString())).load().migrate();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("hours-e2e-cli", Map.of(
            "spring.config.location", "classpath:/application.yml", "spring.config.import", "",
            "spring.flyway.enabled", false, "festival.id", FESTIVAL.toString(),
            "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
            "spring.main.banner-mode", "off", "logging.level.root", "WARN"
        )));
        try (ConfigurableApplicationContext cli = new SpringApplicationBuilder(CatalogCliApplication.class, Configuration.class)
            .environment(environment).profiles("db", "catalog-cli").web(WebApplicationType.NONE).run()) {
            JdbcTemplate jdbc = cli.getBean(JdbcTemplate.class);
            jdbc.update("""
                INSERT INTO festivals (id, title, timezone, created_at, updated_at)
                VALUES (?, 'Synthetic Hours E2E', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, FESTIVAL);
            CatalogCliRunner runner = cli.getBean(CatalogCliRunner.class);
            runner.run(new DefaultApplicationArguments("import",
                "--manifest=" + Path.of("dev/catalog/frontend-mock-catalog.json").toAbsolutePath(),
                "--festival-id=" + FESTIVAL, "--baseline-revision=none", "--actor=hours-e2e"));
            UUID draft = jdbc.queryForObject("""
                SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'draft'
                ORDER BY revision_number DESC LIMIT 1
                """, UUID.class, FESTIVAL);
            initialRevision = draft;
            runner.run(new DefaultApplicationArguments("publish", "--revision=" + draft, "--actor=hours-e2e"));
            assertThat(jdbc.queryForObject("SELECT state FROM festival_revisions WHERE id = ?", String.class, draft))
                .isEqualTo("published");
        }
        prepared = true;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean(name = "dataSource")
        @Primary
        DataSource containerDataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(POSTGRES.getJdbcUrl());
            source.setUsername(POSTGRES.getUsername());
            source.setPassword(POSTGRES.getPassword());
            return source;
        }

        @Bean
        @Primary
        Clock hoursClock() {
            return CLOCK;
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-29T03:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}

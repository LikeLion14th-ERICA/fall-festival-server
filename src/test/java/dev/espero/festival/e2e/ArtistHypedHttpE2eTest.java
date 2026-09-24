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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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

/** Real HTTP and disposable PostgreSQL verification of anonymous artist Hyped participation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "spring.flyway.enabled=false",
    "festival.admin-auth.jwt-signing-secret=hyped-http-e2e-test-signing-secret-32-bytes",
    "festival.admin-auth.allowed-origin=https://admin.hyped.test",
    "festival.admin-auth.bootstrap-username=hyped-e2e-admin",
    "festival.admin-auth.bootstrap-password=hyped-e2e-test-password",
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
@Import(ArtistHypedHttpE2eTest.Configuration.class)
@Testcontainers
@Timeout(180)
class ArtistHypedHttpE2eTest {

    private static final UUID FESTIVAL = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MutableClock CLOCK = new MutableClock();
    private static volatile boolean prepared;

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
        jdbc.update("DELETE FROM artist_hyped_counts WHERE festival_id = ?", FESTIVAL);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        assertThat(get("/readyz").statusCode()).isEqualTo(200);
    }

    @AfterEach
    void closeClient() {
        http.close();
    }

    @Test
    void anonymousClicksFollowPublishedArtistsAndFestivalCalendarThroughHttp() throws Exception {
        HttpResponse<String> config = get("/api/v2/config");
        assertThat(config.statusCode()).isEqualTo(200);
        List<String> festivalDates = JsonPath.read(config.body(), "$.data.festival.dates[*]");
        assertThat(festivalDates).hasSizeGreaterThan(1);
        LocalDate firstDay = LocalDate.parse(festivalDates.getFirst());
        LocalDate secondDay = LocalDate.parse(festivalDates.get(1));
        LocalDate lastDay = LocalDate.parse(festivalDates.getLast());
        Instant opening = firstDay.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        String artistId = artistId();
        String contestId = jdbc.queryForObject("""
            SELECT artist.id FROM artists artist
            JOIN festival_revisions revision ON revision.id = artist.festival_revision_id
            WHERE revision.festival_id = ? AND revision.state = 'published' AND artist.category = 'CONTEST'
            ORDER BY artist.id LIMIT 1
            """, String.class, FESTIVAL);
        assertThat(contestId).isNotBlank();

        CLOCK.now = opening.minusNanos(1);
        assertThat(enabled()).isFalse();
        assertError(post(artistId), 409, "HYPED_CLOSED");
        assertThat(count(artistId)).isZero();

        CLOCK.now = opening;
        assertThat(enabled()).isTrue();
        assertIncrement(post(artistId), artistId, 1);
        assertIncrement(post(artistId), artistId, 2);
        assertThat(count(artistId)).isEqualTo(2);
        assertError(post(contestId), 404, "NOT_FOUND");
        assertError(post("missing-hyped-artist"), 404, "NOT_FOUND");
        assertThat(count(artistId)).isEqualTo(2);

        CLOCK.now = secondDay.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        assertThat(enabled()).isTrue();
        assertIncrement(post(artistId), artistId, 3);
        CLOCK.now = lastDay.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul"))
            .minusNanos(1).toInstant();
        assertIncrement(post(artistId), artistId, 4);
        CLOCK.now = lastDay.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        assertThat(enabled()).isFalse();
        assertError(post(artistId), 409, "HYPED_CLOSED");
        assertThat(count(artistId)).isEqualTo(4);
        assertThat(dbCount(artistId)).isEqualTo(4);
    }

    /** Two hot-row writer stages mixed with public polling. */
    @Test
    void concurrentHypedLoadKeepsEverySuccessfulHttpClick() throws Exception {
        String artistId = artistId();
        List<StageResult> stages = new ArrayList<>();
        Path report = Path.of("target", "hyped-load-results", "summary.json");
        for (StagePlan plan : List.of(
            new StagePlan("writers-8-readers-4", 8, 4, Duration.ofSeconds(10)),
            new StagePlan("writers-24-readers-8", 24, 8, Duration.ofSeconds(10))
        )) {
            StageResult stage = runStage(plan, artistId);
            stages.add(stage);
            Files.createDirectories(report.getParent());
            Files.writeString(report, JSON.writeValueAsString(Map.of(
                "target", "disposable-postgres-loopback-http",
                "rateLimitMode", "disabled-for-capacity-measurement",
                "festivalDay", "2026-09-29",
                "stages", stages
            )));
            assertThat(stage.failures()).as(stage.name()).isEmpty();
            assertThat(stage.postCount()).as(stage.name()).isPositive();
            assertThat(stage.getCount()).as(stage.name()).isPositive();
            assertThat(stage.finalDbCount()).as(stage.name())
                .isEqualTo(stage.initialDbCount() + stage.postCount());
        }
    }

    private StageResult runStage(StagePlan plan, String artistId) throws Exception {
        var postLatencies = new ConcurrentLinkedQueue<Long>();
        var getLatencies = new ConcurrentLinkedQueue<Long>();
        var failureSamples = new ConcurrentLinkedQueue<String>();
        var failureCount = new AtomicInteger();
        var postCount = new LongAdder();
        var getCount = new LongAdder();
        var deadline = new AtomicLong();
        long initialDbCount = dbCount(artistId);
        int workers = plan.writers() + plan.readers();
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>();
        long started = 0;
        try {
            for (int worker = 0; worker < workers; worker++) {
                boolean writer = worker < plan.writers();
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    while (System.nanoTime() < deadline.get()) {
                        long requestStart = System.nanoTime();
                        try {
                            HttpResponse<String> response = writer ? post(artistId) : get("/api/v2/artist-hyped");
                            long latency = System.nanoTime() - requestStart;
                            if (response.statusCode() != 200) {
                                recordFailure(failureCount, failureSamples,
                                    (writer ? "POST" : "GET") + " status=" + response.statusCode());
                            } else if (writer) {
                                postCount.increment();
                                postLatencies.add(latency);
                            } else {
                                Number observed = countFromBody(response.body(), artistId);
                                if (observed.longValue() < 0) {
                                    recordFailure(failureCount, failureSamples, "GET negative count");
                                } else {
                                    getCount.increment();
                                    getLatencies.add(latency);
                                }
                            }
                        } catch (Exception exception) {
                            recordFailure(failureCount, failureSamples,
                                (writer ? "POST" : "GET") + " " + exception.getClass().getSimpleName());
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            started = System.nanoTime();
            deadline.set(started + plan.duration().toNanos());
            start.countDown();
            for (Future<?> task : tasks) task.get(plan.duration().plusSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
        long elapsed = System.nanoTime() - started;
        HttpResponse<String> finalRead = get("/api/v2/artist-hyped");
        assertThat(finalRead.statusCode()).isEqualTo(200);
        long finalHttpCount = countFromBody(finalRead.body(), artistId).longValue();
        long finalDbCount = dbCount(artistId);
        if (finalHttpCount != finalDbCount) {
            recordFailure(failureCount, failureSamples, "final HTTP and DB count differ");
        }
        return new StageResult(plan.name(), plan.writers(), plan.readers(),
            TimeUnit.NANOSECONDS.toMillis(elapsed), initialDbCount, finalDbCount,
            postCount.sum(), getCount.sum(),
            Math.round(postCount.sum() * 1_000_000_000_000.0 / elapsed) / 1_000.0,
            Math.round(getCount.sum() * 1_000_000_000_000.0 / elapsed) / 1_000.0,
            failureCount.get(), List.copyOf(failureSamples),
            percentileMillis(postLatencies, 95), percentileMillis(postLatencies, 99),
            percentileMillis(getLatencies, 95), percentileMillis(getLatencies, 99));
    }

    private static void recordFailure(AtomicInteger count, ConcurrentLinkedQueue<String> samples, String label) {
        if (count.incrementAndGet() <= 10) samples.add(label);
    }

    private static double percentileMillis(ConcurrentLinkedQueue<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> ordered = values.stream().sorted().toList();
        int index = (int) Math.ceil(ordered.size() * percentile / 100.0) - 1;
        return Math.round(ordered.get(index) / 10_000.0) / 100.0;
    }

    private String artistId() throws Exception {
        HttpResponse<String> summary = get("/api/v2/artist-hyped");
        assertThat(summary.statusCode()).isEqualTo(200);
        assertNoStore(summary);
        assertThat(JsonPath.<Number>read(summary.body(), "$.meta.revision").intValue()).isZero();
        List<String> ids = JsonPath.read(summary.body(), "$.data.items[*].artistId");
        assertThat(ids).isNotEmpty();
        return ids.getFirst();
    }

    private boolean enabled() throws Exception {
        HttpResponse<String> response = get("/api/v2/artist-hyped");
        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        return JsonPath.read(response.body(), "$.data.hypedEnabled");
    }

    private long count(String artistId) throws Exception {
        HttpResponse<String> response = get("/api/v2/artist-hyped");
        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        return countFromBody(response.body(), artistId).longValue();
    }

    private static Number countFromBody(String body, String artistId) {
        List<Map<String, Object>> items = JsonPath.read(body, "$.data.items");
        return items.stream().filter(item -> artistId.equals(item.get("artistId")))
            .map(item -> (Number) item.get("hypedCount")).findFirst().orElseThrow();
    }

    private long dbCount(String artistId) {
        Long count = jdbc.queryForObject("""
            SELECT COALESCE((SELECT hyped_count FROM artist_hyped_counts
                WHERE festival_id = ? AND artist_id = ?), 0)
            """, Long.class, FESTIVAL, artistId);
        return count == null ? 0 : count;
    }

    private void assertIncrement(HttpResponse<String> response, String artistId, long count) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        assertThat(JsonPath.<String>read(response.body(), "$.data.artistId")).isEqualTo(artistId);
        assertThat(JsonPath.<Number>read(response.body(), "$.data.hypedCount").longValue()).isEqualTo(count);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertNoStore(response);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo(code);
    }

    private static void assertNoStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
            value -> assertThat(value).contains("no-store"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String artistId) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v2/artists/" + artistId + "/hyped"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static synchronized void prepareCandidate() throws Exception {
        if (prepared) return;
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .placeholders(Map.of("festivalId", FESTIVAL.toString())).load().migrate();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("hyped-e2e-cli", Map.of(
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
                VALUES (?, 'Synthetic Hyped E2E', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, FESTIVAL);
            CatalogCliRunner runner = cli.getBean(CatalogCliRunner.class);
            runner.run(new DefaultApplicationArguments("import",
                "--manifest=" + Path.of("dev/catalog/frontend-mock-catalog.json").toAbsolutePath(),
                "--festival-id=" + FESTIVAL, "--baseline-revision=none", "--actor=hyped-e2e"));
            UUID draft = jdbc.queryForObject("""
                SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'draft'
                ORDER BY revision_number DESC LIMIT 1
                """, UUID.class, FESTIVAL);
            runner.run(new DefaultApplicationArguments("publish", "--revision=" + draft, "--actor=hyped-e2e"));
            assertThat(jdbc.queryForObject("SELECT state FROM festival_revisions WHERE id = ?", String.class, draft))
                .isEqualTo("published");
        }
        prepared = true;
    }

    private record StagePlan(String name, int writers, int readers, Duration duration) {}
    private record StageResult(String name, int writers, int readers, long elapsedMs,
                               long initialDbCount, long finalDbCount, long postCount, long getCount,
                               double postRps, double getRps, long failureCount, List<String> failures,
                               double postP95Ms, double postP99Ms,
                               double getP95Ms, double getP99Ms) {}

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
        Clock hypedClock() {
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

package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import dev.espero.festival.CatalogRevisionService;
import dev.espero.festival.web.CatalogSnapshotProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cross-process release check for the revision-independent TICKET account.
 * The web process serves a published ticket fixture while each account write
 * goes through the real AccountSettingsCliApplication child JVM.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "server.address=127.0.0.1",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
            + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration"
    }
)
@ActiveProfiles("db")
@Import(OperationalAccountPropagationE2eTest.ClockConfiguration.class)
@Testcontainers
class OperationalAccountPropagationE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final OffsetDateTime IN_WINDOW = OffsetDateTime.parse("2026-09-29T12:00:00+09:00");
    private static final String ACCOUNT_NUMBER = "110-0000-9876";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.hikari.username", POSTGRES::getUsername);
        registry.add("spring.datasource.hikari.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.festivalId", FESTIVAL_ID::toString);
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
            .load()
            .migrate();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogRevisionService revisions;

    @Autowired
    private CatalogSnapshotProvider snapshots;

    @Autowired
    private MutableClock clock;

    @TempDir
    private Path tempDirectory;

    @BeforeEach
    void publishTicketFixtureAndStartFromAnUnconfiguredAccount() throws Exception {
        clock.set(IN_WINDOW.toInstant());
        jdbc.update("DELETE FROM operational_account_settings");
        jdbc.update("DELETE FROM operational_account_setting_history");
        publishTicketFixture();
    }

    @Test
    void cliSetAndClearPropagateToHttpWithConditionalEtagSemantics() throws Exception {
        HttpResponse<String> initial = ticketGuide();
        assertThat(initial.statusCode()).isEqualTo(200);
        assertThat(jsonString(initial.body(), "$.data.status")).isEqualTo("UNCONFIGURED");
        assertThat(nullableJsonValue(initial.body(), "$.data.account")).isNull();
        assertThat(nullableJsonValue(initial.body(), "$.data.paymentSettingsVersion")).isNull();
        String initialEtag = strongEtag(initial);

        Path input = tempDirectory.resolve("ticket-account.json");
        Files.writeString(input, """
            {"bankName":"Test Bank","accountNumber":"110-0000-9876","accountHolder":"Test Holder","transferLinkUrl":null}
            """, StandardCharsets.UTF_8);
        ProcessResult set = accountCli(
            "set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET", "--expected-version=0",
            "--input-file=" + input, "--last-four=9876", "--confirm",
            "--actor=account-propagation-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-PROP-1"
        );
        assertSuccess(set);
        assertThat(set.output()).contains("mode=APPLIED", "state=CONFIGURED", "resultVersion=1", "accountLastFour=9876")
            .doesNotContain(ACCOUNT_NUMBER, "Test Bank", "Test Holder", POSTGRES.getPassword(), POSTGRES.getJdbcUrl());

        HttpResponse<String> configured = ticketGuide();
        assertThat(configured.statusCode()).isEqualTo(200);
        assertThat(jsonString(configured.body(), "$.data.status")).isEqualTo("TRANSFER_OPEN");
        assertThat(jsonString(configured.body(), "$.data.account.accountNumber")).isEqualTo(ACCOUNT_NUMBER);
        assertThat(jsonNumber(configured.body(), "$.data.paymentSettingsVersion").longValue()).isEqualTo(1L);
        String configuredEtag = strongEtag(configured);
        assertThat(configuredEtag).isNotEqualTo(initialEtag);

        HttpResponse<String> stale = ticketGuide(initialEtag);
        assertThat(stale.statusCode()).isEqualTo(200);
        assertThat(jsonString(stale.body(), "$.data.account.accountNumber")).isEqualTo(ACCOUNT_NUMBER);
        HttpResponse<String> matching = ticketGuide(configuredEtag);
        assertThat(matching.statusCode()).isEqualTo(304);
        assertThat(matching.body()).isEmpty();
        assertThat(requiredHeader(matching, "ETag")).isEqualTo(configuredEtag);
        assertThat(requiredHeader(matching, "Cache-Control")).contains("private", "no-cache");

        clock.set(OffsetDateTime.parse("2026-09-29T18:00:00+09:00").toInstant());
        HttpResponse<String> closed = ticketGuide();
        assertThat(jsonString(closed.body(), "$.data.status")).isEqualTo("DAILY_CLOSED");
        assertThat(nullableJsonValue(closed.body(), "$.data.account")).isNull();
        assertThat(strongEtag(closed)).isNotEqualTo(configuredEtag);
        clock.set(IN_WINDOW.toInstant());

        ProcessResult clear = accountCli(
            "clear", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET", "--expected-version=1", "--confirm",
            "--actor=account-propagation-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-PROP-2"
        );
        assertSuccess(clear);
        assertThat(clear.output()).contains("mode=APPLIED", "state=UNCONFIGURED", "resultVersion=2")
            .doesNotContain(ACCOUNT_NUMBER, "Test Bank", "Test Holder", POSTGRES.getPassword(), POSTGRES.getJdbcUrl());

        HttpResponse<String> cleared = ticketGuide();
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(jsonString(cleared.body(), "$.data.status")).isEqualTo("UNCONFIGURED");
        assertThat(nullableJsonValue(cleared.body(), "$.data.account")).isNull();
        assertThat(jsonNumber(cleared.body(), "$.data.paymentSettingsVersion").longValue()).isEqualTo(2L);
        String clearedEtag = strongEtag(cleared);
        assertThat(clearedEtag).isNotEqualTo(configuredEtag);
        assertThat(ticketGuide(configuredEtag).statusCode()).isEqualTo(200);
        assertThat(ticketGuide(clearedEtag).statusCode()).isEqualTo(304);
    }

    private void publishTicketFixture() throws Exception {
        String manifest = Files.readString(Path.of("dev", "catalog", "development-catalog.json"))
            .replace("\"unitPriceAmount\": null", "\"unitPriceAmount\": 5000")
            .replace("\"festivalStartDate\": null", "\"festivalStartDate\": \"2026-09-29\"")
            .replace("\"festivalEndDate\": null", "\"festivalEndDate\": \"2026-10-01\"")
            .replace("\"dailyTransferOpenTime\": null", "\"dailyTransferOpenTime\": \"10:00:00\"")
            .replace("\"dailyTransferCloseTime\": null", "\"dailyTransferCloseTime\": \"18:00:00\"")
            .replace("\"dailyPickupOpenTime\": null", "\"dailyPickupOpenTime\": \"11:00:00\"")
            .replace("\"dailyPickupCloseTime\": null", "\"dailyPickupCloseTime\": \"20:00:00\"");
        Path fixture = tempDirectory.resolve("ticket-propagation-catalog.json");
        Files.writeString(fixture, manifest, StandardCharsets.UTF_8);
        UUID baseline = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'published'",
            UUID.class,
            FESTIVAL_ID
        );
        UUID revision = revisions.importManifest(
            fixture, "account-propagation-e2e", FESTIVAL_ID, new CatalogRevisionService.BaselineOverride(baseline)
        );
        revisions.publish(revision, "account-propagation-e2e");
        snapshots.run(null);
    }

    private HttpResponse<String> ticketGuide() throws Exception {
        return ticketGuide(null);
    }

    private HttpResponse<String> ticketGuide(String etag) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v2/ticket-guide"))
            .GET();
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ProcessResult accountCli(String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dspring.main.banner-mode=off",
            "-Dspring.jmx.enabled=false",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            "dev.espero.festival.AccountSettingsCliApplication"
        ));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(tempDirectory.toFile())
            .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        copyOsEnvironment(environment);
        environment.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        environment.put("SPRING_DATASOURCE_HIKARI_JDBC_URL", POSTGRES.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_HIKARI_USERNAME", POSTGRES.getUsername());
        environment.put("SPRING_DATASOURCE_HIKARI_PASSWORD", POSTGRES.getPassword());
        environment.put("SPRING_FLYWAY_ENABLED", "true");
        environment.put("FESTIVAL_ID", FESTIVAL_ID.toString());
        Process process = builder.start();
        FutureTask<String> outputTask = new FutureTask<>(
            () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        );
        Thread.startVirtualThread(outputTask);
        try {
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("account CLI did not terminate within 45 seconds");
            }
            String output = outputTask.get(5, TimeUnit.SECONDS);
            return new ProcessResult(process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outputTask.cancel(true);
        }
    }

    private void copyOsEnvironment(Map<String, String> target) {
        Map<String, String> source = System.getenv();
        for (String name : List.of(
            "ComSpec", "HOME", "HOMEDRIVE", "HOMEPATH", "LANG", "LC_ALL", "LOCALAPPDATA", "PATH", "PATHEXT",
            "Path", "SystemRoot", "TEMP", "TMP", "TMPDIR", "USERPROFILE", "WINDIR"
        )) {
            String value = source.get(name);
            if (value != null) {
                target.put(name, value);
            }
        }
    }

    private void assertSuccess(ProcessResult result) {
        assertThat(result.exitCode()).as("account CLI must exit successfully").isZero();
    }

    private String strongEtag(HttpResponse<String> response) {
        String etag = requiredHeader(response, "ETag");
        assertThat(etag).matches("\"[0-9a-f]{64}\"");
        return etag;
    }

    private String requiredHeader(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow(() -> new AssertionError(
            "Expected " + name + " on HTTP " + response.statusCode()
        ));
    }

    private static String jsonString(String body, String path) {
        return (String) JsonPath.read(body, path);
    }

    private static Object jsonValue(String body, String path) {
        return JsonPath.read(body, path);
    }

    private static Object nullableJsonValue(String body, String path) {
        try {
            return jsonValue(body, path);
        } catch (com.jayway.jsonpath.PathNotFoundException exception) {
            return null;
        }
    }

    private static Number jsonNumber(String body, String path) {
        return (Number) JsonPath.read(body, path);
    }

    private record ProcessResult(int exitCode, String output) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(IN_WINDOW.toInstant());
        }

        @Bean(name = "dataSource")
        @Primary
        DataSource accountPropagationE2eDataSource() {
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

        void set(Instant value) {
            this.instant = value;
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

package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogCliRunner;
import dev.espero.festival.FallFestivalServerApplication;
import dev.espero.festival.account.OperationalAccountAuditMetadata;
import dev.espero.festival.account.OperationalAccountChange;
import dev.espero.festival.account.OperationalAccountPurpose;
import dev.espero.festival.account.OperationalAccountSetting;
import dev.espero.festival.account.OperationalAccountSettingsService;
import dev.espero.festival.account.OperationalAccountState;
import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.persistence.CrowdingStore;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real publication transactions and HTTP servers sharing only an ephemeral database. */
@Testcontainers
class CatalogPublicationLifecycleE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final LocalDate OPERATING_DATE = LocalDate.of(2026, 9, 29);
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final String ACTOR = "publication-lifecycle-e2e";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @TempDir
    Path temporaryDirectory;

    @Test
    void publicationAndRollbackBecomeVisibleAfterRestartWithoutChangingDynamicState() throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
            .load()
            .migrate();
        Candidate candidateA = candidate("a");
        Candidate candidateB = candidate("b");

        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
             ConfigurableApplicationContext cli = startCatalogCli()) {
            JdbcTemplate jdbc = cli.getBean(JdbcTemplate.class);
            CatalogCliRunner commands = cli.getBean(CatalogCliRunner.class);
            Publication publicationA = importAndPublish(commands, jdbc, candidateA, published(jdbc));
            Publication publicationB;
            Publication restoredA;
            DynamicState dynamic;
            String ticketEtagA;
            String ticketEtagB;

            try (ConfigurableApplicationContext server = startServer()) {
                dynamic = seedDynamicState(http, server);
                ticketEtagA = assertServing(http, server, candidateA, publicationA, dynamic);

                publicationB = importAndPublish(commands, jdbc, candidateB, publicationA);
                assertThat(publicationB.number()).isGreaterThan(publicationA.number());
                // Publication changes the database; this process keeps its loaded snapshot.
                assertThat(assertServing(http, server, candidateA, publicationA, dynamic))
                    .isEqualTo(ticketEtagA);
            }

            try (ConfigurableApplicationContext server = startServer()) {
                ticketEtagB = assertServing(http, server, candidateB, publicationB, dynamic);
                assertThat(ticketEtagB).isNotEqualTo(ticketEtagA);

                commands.run(new DefaultApplicationArguments(
                    "rollback", "--revision=" + publicationA.id(),
                    "--expected-current=" + publicationB.id(), "--actor=" + ACTOR
                ));
                restoredA = published(jdbc);
                assertThat(restoredA.id()).isNotIn(publicationA.id(), publicationB.id());
                assertThat(restoredA.number()).isGreaterThan(publicationB.number());
                assertThat(jdbc.queryForObject(
                    "SELECT base_revision_id FROM festival_revisions WHERE id = ?",
                    UUID.class, restoredA.id()
                )).isEqualTo(publicationB.id());
                assertThat(assertServing(http, server, candidateB, publicationB, dynamic))
                    .isEqualTo(ticketEtagB);
            }

            try (ConfigurableApplicationContext server = startServer()) {
                // Rollback restores A's content and map versions under a new catalog revision.
                assertThat(assertServing(http, server, candidateA, restoredA, dynamic))
                    .isNotIn(ticketEtagA, ticketEtagB);
            }

            assertThat(jdbc.queryForList(
                "SELECT state FROM festival_revisions WHERE id IN (?, ?)",
                String.class, publicationA.id(), publicationB.id()
            )).containsExactly("archived", "archived");
        }
    }

    private Candidate candidate(String suffix) throws Exception {
        String mapVersion = "lifecycle-" + suffix;
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(
            Path.of("dev/catalog/frontend-mock-catalog.json")
        ).replace("\"overview-v1\"", "\"" + mapVersion + "\""));
        String spaceName = "Lifecycle space " + suffix;
        String performanceTitle = "Lifecycle performance " + suffix;
        ((ObjectNode) manifest.path("spaceTranslations").get(0)).put("name", spaceName);
        ((ObjectNode) manifest.path("performanceTranslations").get(0)).put("title", performanceTitle);
        Path path = temporaryDirectory.resolve("candidate-" + suffix + ".json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), manifest);
        return new Candidate(path, spaceName, performanceTitle, mapVersion);
    }

    private Publication importAndPublish(
        CatalogCliRunner commands, JdbcTemplate jdbc, Candidate candidate, Publication baseline
    ) {
        commands.run(new DefaultApplicationArguments(
            "import", "--manifest=" + candidate.path(), "--festival-id=" + FESTIVAL_ID,
            "--baseline-revision=" + baseline.id(), "--actor=" + ACTOR
        ));
        UUID draft = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'draft'",
            UUID.class, FESTIVAL_ID
        );
        commands.run(new DefaultApplicationArguments(
            "publish", "--revision=" + draft, "--actor=" + ACTOR
        ));
        Publication publication = published(jdbc);
        assertThat(publication.id()).isEqualTo(draft);
        return publication;
    }

    private Publication published(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
            SELECT id, revision_number FROM festival_revisions
            WHERE festival_id = ? AND state = 'published'
            """, (row, index) -> new Publication(row.getObject("id", UUID.class), row.getLong("revision_number")),
            FESTIVAL_ID);
    }

    private DynamicState seedDynamicState(HttpClient http, ConfigurableApplicationContext server) throws Exception {
        OperationalAccountSettingsService accounts = server.getBean(OperationalAccountSettingsService.class);
        accounts.set(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0,
            new OperationalAccountChange(
                OperationalAccountState.CONFIGURED, "Lifecycle Test Bank", "001100001234", "Test Holder", null
            ),
            "1234", new OperationalAccountAuditMetadata(ACTOR, "Local lifecycle verification", "LIFECYCLE-E2E")
        );
        CrowdingStore crowding = server.getBean(CrowdingStore.class);
        crowding.save(FESTIVAL_ID, OPERATING_DATE, "CROWDED", NOW.minusSeconds(300));
        HttpResponse<String> response = get(http, server, "/api/v2/crowding");
        assertThat(response.statusCode()).isEqualTo(200);
        return new DynamicState(
            accounts.findCurrent(FESTIVAL_ID, OperationalAccountPurpose.TICKET).orElseThrow(),
            crowding.findFor(FESTIVAL_ID, OPERATING_DATE).orElseThrow(),
            response.headers().firstValue("ETag").orElseThrow()
        );
    }

    private String assertServing(
        HttpClient http, ConfigurableApplicationContext server, Candidate candidate,
        Publication publication, DynamicState dynamic
    ) throws Exception {
        assertThat(get(http, server, "/readyz").statusCode()).isEqualTo(200);
        JsonNode space = catalogResponse(get(http, server, "/api/v2/spaces/mock-pub-moonlight"), publication);
        assertThat(space.at("/data/name").asText()).isEqualTo(candidate.spaceName());
        JsonNode performance = catalogResponse(get(http, server, "/api/v2/performances/mock-performance-1"), publication);
        assertThat(performance.at("/data/title").asText()).isEqualTo(candidate.performanceTitle());
        JsonNode map = catalogResponse(get(http, server, "/api/v2/maps/map-mock-overview"), publication);
        assertThat(map.at("/data/version").asText()).isEqualTo(candidate.mapVersion());
        JsonNode pins = catalogResponse(get(
            http, server, "/api/v2/maps/map-mock-overview/pins?mapVersion=" + candidate.mapVersion()
        ), publication);
        assertThat(pins.at("/data/mapVersion").asText()).isEqualTo(candidate.mapVersion());
        assertThat(pins.at("/data/items").isEmpty()).isFalse();

        HttpResponse<String> ticketResponse = get(http, server, "/api/v2/ticket-guide");
        JsonNode ticket = catalogResponse(ticketResponse, publication);
        assertThat(ticket.at("/data/mapTarget/mapVersion").asText()).isEqualTo(candidate.mapVersion());
        assertThat(ticket.at("/data/status").asText()).isEqualTo("TRANSFER_OPEN");
        assertThat(ticket.at("/data/paymentSettingsVersion").asLong()).isEqualTo(dynamic.account().version());
        assertThat(ticket.at("/data/account/bankName").asText()).isEqualTo(dynamic.account().bankName());
        assertThat(ticket.at("/data/account/accountNumber").asText()).isEqualTo(dynamic.account().accountNumber());
        assertThat(ticket.at("/data/account/holder").asText()).isEqualTo(dynamic.account().accountHolder());

        HttpResponse<String> crowdingResponse = get(http, server, "/api/v2/crowding");
        JsonNode crowding = successfulBody(crowdingResponse);
        assertThat(crowding.at("/data/status").asText()).isEqualTo("CROWDED");
        assertThat(crowding.at("/data/operatingStatus").asText()).isEqualTo("OPEN");
        assertThat(crowding.at("/data/timeBasis").asText()).isEqualTo("OPERATOR");
        assertThat(OffsetDateTime.parse(crowding.at("/data/updatedAt").asText()).toInstant())
            .isEqualTo(dynamic.crowding().updatedAt());
        assertThat(crowding.at("/meta/revision").asLong()).isZero();
        assertThat(crowdingResponse.headers().firstValue("ETag").orElseThrow()).isEqualTo(dynamic.crowdingEtag());

        assertThat(server.getBean(OperationalAccountSettingsService.class)
            .findCurrent(FESTIVAL_ID, OperationalAccountPurpose.TICKET)).contains(dynamic.account());
        assertThat(server.getBean(CrowdingStore.class).findFor(FESTIVAL_ID, OPERATING_DATE))
            .contains(dynamic.crowding());
        JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM operational_account_setting_history WHERE festival_id = ? AND purpose = 'TICKET'
            """, Long.class, FESTIVAL_ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM crowding_state_dynamic WHERE festival_id = ?", Long.class, FESTIVAL_ID
        )).isEqualTo(1L);
        return ticketResponse.headers().firstValue("ETag").orElseThrow();
    }

    private JsonNode catalogResponse(HttpResponse<String> response, Publication publication) {
        JsonNode body = successfulBody(response);
        assertThat(body.at("/meta/festivalId").asText()).isEqualTo(FESTIVAL_ID.toString());
        assertThat(body.at("/meta/revision").asLong()).isEqualTo(publication.number());
        assertThat(body.at("/meta/locale").asText()).isEqualTo("ko");
        assertThat(body.at("/meta/mock").asBoolean()).isFalse();
        return body;
    }

    private JsonNode successfulBody(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("GET %s: %s", response.uri().getPath(), response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(HttpClient http, ConfigurableApplicationContext server, String path) throws Exception {
        int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private ConfigurableApplicationContext startCatalogCli() throws Exception {
        return assertContainerDatasource(new SpringApplicationBuilder(CatalogCliApplication.class)
            .environment(containerEnvironment()).profiles("db", "catalog-cli").web(WebApplicationType.NONE).run());
    }

    private ConfigurableApplicationContext startServer() throws Exception {
        return assertContainerDatasource(new SpringApplicationBuilder(
            FallFestivalServerApplication.class, ClockConfiguration.class
        ).environment(containerEnvironment()).profiles("db").web(WebApplicationType.SERVLET).run());
    }

    private ConfigurableApplicationContext assertContainerDatasource(ConfigurableApplicationContext context) throws Exception {
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            return context;
        } catch (Exception | AssertionError failure) {
            context.close();
            throw failure;
        }
    }

    private StandardEnvironment containerEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        // Do not let shell/JVM datasource, Hikari, JNDI, profiles, or config imports reach this test.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("lifecycle-e2e", Map.ofEntries(
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
            Map.entry("spring.main.log-startup-info", false),
            Map.entry("logging.level.root", "WARN"),
            Map.entry("server.address", "127.0.0.1"),
            Map.entry("server.port", 0),
            Map.entry("festival.id", FESTIVAL_ID.toString()),
            Map.entry("festival.rate-limit.enabled", false),
            Map.entry("festival.admin-auth.jwt-signing-secret", "lifecycle-e2e-test-signing-secret-32-bytes"),
            Map.entry("festival.admin-auth.allowed-origin", "https://admin.lifecycle.test")
        )));
        return environment;
    }

    private record Candidate(Path path, String spaceName, String performanceTitle, String mapVersion) {}

    private record Publication(UUID id, long number) {}

    private record DynamicState(OperationalAccountSetting account, CrowdingRecord crowding, String crowdingEtag) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        Clock lifecycleClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

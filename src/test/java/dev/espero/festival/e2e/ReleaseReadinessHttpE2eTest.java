package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogCliRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Release gate for a candidate catalog. It seeds only an ephemeral PostgreSQL
 * container, publishes the candidate through the real catalog CLI before the
 * web process starts, then exercises the deployed HTTP boundary.
 *
 * <p>Set {@value #CANDIDATE_MANIFEST_PROPERTY} to a candidate manifest path
 * to run the same gate against a future release candidate. It intentionally
 * never reads a configured development or production datasource.</p>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "server.address=127.0.0.1",
        "festival.admin-auth.jwt-signing-secret=backend-e2e-test-signing-secret-32-bytes",
        "festival.admin-auth.allowed-origin=https://admin.e2e.test",
        "festival.admin-auth.bootstrap-username=release-e2e-admin",
        "festival.admin-auth.bootstrap-password=release-e2e-password"
    }
)
@ActiveProfiles("db")
@Import(ReleaseReadinessHttpE2eTest.ClockConfiguration.class)
@Testcontainers
class ReleaseReadinessHttpE2eTest {

    static final String CANDIDATE_MANIFEST_PROPERTY = "festival.release-e2e.manifest";

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String ADMIN_ORIGIN = "https://admin.e2e.test";
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
        registry.add("spring.flyway.placeholders.festivalId", FESTIVAL_ID::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void candidatePublishesBeforeStartupAndPassesPublicAndAdminHttpReleaseChecks() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        HttpResponse<String> health = send(HttpRequest.newBuilder(uri("/healthz")).GET().build());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"ok\"}");

        HttpResponse<String> readiness = send(HttpRequest.newBuilder(uri("/readyz")).GET().build());
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"ready\"}");

        HttpResponse<String> config = send(HttpRequest.newBuilder(uri("/api/v2/config")).GET().build());
        assertThat(config.statusCode()).isEqualTo(200);
        assertCandidateMeta(config.body(), candidate);
        assertThat(jsonString(config.body(), "$.data.festival.id")).isEqualTo(FESTIVAL_ID.toString());
        assertThat(jsonString(config.body(), "$.data.festival.title")).isNotBlank();

        assertPublicCatalogRoutes(candidate);
        assertAnonymousAdminRequestsAreRejected();

        HttpResponse<String> firstPublic = send(HttpRequest.newBuilder(uri("/api/v2/crowding"))
            .header("X-Request-Id", "client-controlled-id")
            .GET()
            .build());
        assertThat(firstPublic.statusCode()).isEqualTo(200);
        assertThat(jsonString(firstPublic.body(), "$.data.operatingStatus")).isEqualTo("OPEN");
        assertThat(jsonString(firstPublic.body(), "$.data.status")).isEqualTo("RELAXED");
        assertThat(jsonNumber(firstPublic.body(), "$.meta.revision").longValue()).isZero();
        String publicRequestId = requiredHeader(firstPublic, "X-Request-Id");
        assertThat(publicRequestId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(publicRequestId).isNotEqualTo("client-controlled-id");
        OffsetDateTime.parse(requiredHeader(firstPublic, "X-Server-Time"));
        String publicEtag = requiredHeader(firstPublic, "ETag");
        assertThat(publicEtag).matches("\"[0-9a-f]{64}\"");

        HttpResponse<String> notModified = send(HttpRequest.newBuilder(uri("/api/v2/crowding"))
            .header("If-None-Match", publicEtag)
            .GET()
            .build());
        assertThat(notModified.statusCode()).isEqualTo(304);
        assertThat(notModified.body()).isEmpty();
        assertThat(requiredHeader(notModified, "ETag")).isEqualTo(publicEtag);
        assertThat(requiredHeader(notModified, "X-Request-Id")).isNotEqualTo(publicRequestId);

        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions"))
            .header("Origin", ADMIN_ORIGIN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"username":"release-e2e-admin","password":"release-e2e-password"}
                """))
            .build());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(requiredHeader(login, "Access-Control-Allow-Origin")).isEqualTo(ADMIN_ORIGIN);
        assertThat(requiredHeader(login, "Set-Cookie")).contains(
            "__Host-festival-admin-refresh=", "Secure", "HttpOnly", "SameSite=Strict"
        );
        String accessToken = jsonString(login.body(), "$.data.accessToken");
        assertThat(accessToken).isNotBlank();

        HttpResponse<String> adminRead = send(HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Origin", ADMIN_ORIGIN)
            .GET()
            .build());
        assertThat(adminRead.statusCode()).isEqualTo(200);
        String adminEtag = requiredHeader(adminRead, "ETag");

        HttpRequest updateRequest = adminCrowdingUpdate(accessToken, adminEtag, "release-e2e-crowding-1", "CROWDED");
        HttpResponse<String> update = send(updateRequest);
        assertThat(update.statusCode()).isEqualTo(204);
        assertThat(update.body()).isEmpty();

        HttpResponse<String> replay = send(updateRequest);
        assertThat(replay.statusCode()).isEqualTo(204);
        assertThat(replay.body()).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE action = 'CROWDING_UPDATED'", Long.class
        )).isEqualTo(1L);

        HttpResponse<String> changedPublic = send(HttpRequest.newBuilder(uri("/api/v2/crowding")).GET().build());
        assertThat(changedPublic.statusCode()).isEqualTo(200);
        assertThat(jsonString(changedPublic.body(), "$.data.status")).isEqualTo("CROWDED");
        assertThat(requiredHeader(changedPublic, "ETag")).isNotEqualTo(publicEtag);

        HttpResponse<String> staleUpdate = send(adminCrowdingUpdate(
            accessToken, adminEtag, "release-e2e-crowding-2", "MODERATE"
        ));
        assertThat(staleUpdate.statusCode()).isEqualTo(409);
        assertThat(jsonString(staleUpdate.body(), "$.error.code")).isEqualTo("EDIT_CONFLICT");
    }

    private void assertPublicCatalogRoutes(PreparedCandidate candidate) throws Exception {
        HttpResponse<String> spaces = candidateResponse("/api/v2/spaces", candidate);
        for (String spaceId : jsonStringList(spaces.body(), "$.data.items[*].id")) {
            HttpResponse<String> detail = candidateResponse("/api/v2/spaces/" + spaceId, candidate);
            assertThat(jsonString(detail.body(), "$.data.id")).isEqualTo(spaceId);
        }

        HttpResponse<String> maps = candidateResponse("/api/v2/maps", candidate);
        Set<String> placeIds = new LinkedHashSet<>();
        for (String mapId : jsonStringList(maps.body(), "$.data.items[*].id")) {
            HttpResponse<String> detail = candidateResponse("/api/v2/maps/" + mapId, candidate);
            assertThat(jsonString(detail.body(), "$.data.id")).isEqualTo(mapId);
            String mapVersion = jsonString(detail.body(), "$.data.version");
            HttpResponse<String> pins = candidateResponse(
                "/api/v2/maps/" + mapId + "/pins?mapVersion=" + mapVersion,
                candidate
            );
            assertThat(jsonString(pins.body(), "$.data.mapId")).isEqualTo(mapId);
            assertThat(jsonString(pins.body(), "$.data.mapVersion")).isEqualTo(mapVersion);
            placeIds.addAll(placeIdsFor(pins.body()));
        }
        for (String placeId : placeIds) {
            HttpResponse<String> detail = candidateResponse("/api/v2/places/" + placeId, candidate);
            assertThat(jsonString(detail.body(), "$.data.id")).isEqualTo(placeId);
        }

        candidateResponse("/api/v2/prohibited-items", candidate);

        HttpResponse<String> ticketGuide = candidateResponse("/api/v2/ticket-guide", candidate);
        assertThat(jsonString(ticketGuide.body(), "$.data.status")).isIn(
            "BEFORE_FESTIVAL", "TRANSFER_OPEN", "DAILY_CLOSED", "FESTIVAL_ENDED", "UNCONFIGURED"
        );
        String ticketEtag = requiredHeader(ticketGuide, "ETag");
        HttpResponse<String> ticketNotModified = send(HttpRequest.newBuilder(uri("/api/v2/ticket-guide"))
            .header("If-None-Match", ticketEtag)
            .GET()
            .build());
        assertThat(ticketNotModified.statusCode()).isEqualTo(304);
        assertThat(ticketNotModified.body()).isEmpty();
        assertThat(requiredHeader(ticketNotModified, "ETag")).isEqualTo(ticketEtag);

        candidateResponse("/api/v2/stamp-guide", candidate);
    }

    private void assertAnonymousAdminRequestsAreRejected() throws Exception {
        HttpResponse<String> read = send(HttpRequest.newBuilder(uri("/api/v2/admin/crowding")).GET().build());
        assertUnauthorized(read);

        HttpResponse<String> write = send(HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .header("Content-Type", "application/json")
            .header("If-Match", "\"0000000000000000000000000000000000000000000000000000000000000000\"")
            .header("Idempotency-Key", "release-e2e-anonymous")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"level\":\"CROWDED\"}"))
            .build());
        assertUnauthorized(write);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_events", Long.class)).isZero();
    }

    private void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(jsonString(response.body(), "$.error.code")).isEqualTo("UNAUTHORIZED");
    }

    private HttpResponse<String> candidateResponse(String path, PreparedCandidate candidate) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).GET().build());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        assertCandidateMeta(response.body(), candidate);
        return response;
    }

    private HttpRequest adminCrowdingUpdate(String accessToken, String etag, String key, String level) {
        return HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Origin", ADMIN_ORIGIN)
            .header("Content-Type", "application/json")
            .header("If-Match", etag)
            .header("Idempotency-Key", key)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"level\":\"" + level + "\"}"))
            .build();
    }

    private void assertRevision(String body, long expectedRevision) {
        Number actual = jsonNumber(body, "$.meta.revision");
        assertThat(actual.longValue()).isEqualTo(expectedRevision);
    }

    private void assertCandidateMeta(String body, PreparedCandidate candidate) {
        assertRevision(body, candidate.revisionNumber());
        assertThat(jsonString(body, "$.meta.festivalId")).isEqualTo(FESTIVAL_ID.toString());
        assertThat(jsonString(body, "$.meta.locale")).isEqualTo("ko");
    }

    private static String jsonString(String body, String path) {
        return (String) JsonPath.read(body, path);
    }

    private static Number jsonNumber(String body, String path) {
        return (Number) JsonPath.read(body, path);
    }

    private static List<String> jsonStringList(String body, String path) {
        return ((List<?>) JsonPath.read(body, path)).stream().map(String.class::cast).toList();
    }

    private static Set<String> placeIdsFor(String body) {
        Set<String> placeIds = new LinkedHashSet<>();
        for (Object pin : (List<?>) JsonPath.read(body, "$.data.items")) {
            if (!(pin instanceof Map<?, ?> pinObject) || !(pinObject.get("target") instanceof Map<?, ?> target)) {
                continue;
            }
            Object placeId = target.get("placeId");
            if (placeId instanceof String value) {
                placeIds.add(value);
            }
        }
        return placeIds;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String requiredHeader(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow(() -> new AssertionError(
            "Expected " + name + " header on HTTP " + response.statusCode()
        ));
    }

    private static PreparedCandidate prepared() {
        PreparedCandidate candidate = preparedCandidate;
        if (candidate == null) {
            throw new IllegalStateException("Release candidate preparation did not complete.");
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
            Path manifest = candidateManifest();
            try {
                POSTGRES.start();
                migrate();
                UUID baseline = publishedRevision();
                UUID revision = importAndPublish(manifest, baseline);
                preparedCandidate = new PreparedCandidate(
                    revisionNumber(revision),
                    midpointOfFirstOperatingWindow(revision)
                );
            } catch (Exception exception) {
                throw new IllegalStateException(
                    "Release candidate could not be migrated, imported, and published: " + manifest,
                    exception
                );
            }
        }
    }

    private static Path candidateManifest() {
        String configured = System.getProperty(CANDIDATE_MANIFEST_PROPERTY);
        Path path = configured == null || configured.isBlank()
            ? Path.of("dev", "catalog", "development-catalog.json")
            : Path.of(configured);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Release candidate manifest does not exist: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
            .load()
            .migrate();
    }

    private static UUID importAndPublish(Path manifest, UUID baseline) {
        try (ConfigurableApplicationContext cli = new SpringApplicationBuilder(CatalogCliApplication.class)
            .profiles("db", "catalog-cli")
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.enabled=false",
                "--festival.id=" + FESTIVAL_ID,
                "--spring.main.banner-mode=off"
            )) {
            CatalogCliRunner runner = cli.getBean(CatalogCliRunner.class);
            runner.run(new DefaultApplicationArguments(
                "import",
                "--manifest=" + manifest,
                "--festival-id=" + FESTIVAL_ID,
                "--baseline-revision=" + baseline,
                "--actor=release-e2e"
            ));
            UUID draft = newestDraftRevision();
            runner.run(new DefaultApplicationArguments(
                "publish",
                "--revision=" + draft,
                "--actor=release-e2e"
            ));
            if (!draft.equals(publishedRevision())) {
                throw new IllegalStateException("Catalog CLI did not make the imported revision published.");
            }
            return draft;
        }
    }

    private static UUID publishedRevision() {
        return querySingleUuid("""
            SELECT id
            FROM festival_revisions
            WHERE festival_id = ?
              AND state = 'published'
            """);
    }

    private static UUID newestDraftRevision() {
        return querySingleUuid("""
            SELECT id
            FROM festival_revisions
            WHERE festival_id = ?
              AND state = 'draft'
            ORDER BY revision_number DESC
            LIMIT 1
            """);
    }

    private static UUID querySingleUuid(String sql) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, FESTIVAL_ID);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Expected exactly one catalog revision row.");
                }
                UUID value = rows.getObject(1, UUID.class);
                if (rows.next()) {
                    throw new IllegalStateException("Expected one catalog revision row, found multiple.");
                }
                return value;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read the candidate catalog revision.", exception);
        }
    }

    private static long revisionNumber(UUID revisionId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT revision_number
                 FROM festival_revisions
                 WHERE id = ?
                 """)) {
            statement.setObject(1, revisionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Published candidate revision disappeared.");
                }
                return rows.getLong(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read the candidate revision number.", exception);
        }
    }

    private static Instant midpointOfFirstOperatingWindow(UUID revisionId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT opens_at, closes_at
                 FROM festival_days
                 WHERE festival_revision_id = ?
                 ORDER BY festival_date
                 LIMIT 1
                 """)) {
            statement.setObject(1, revisionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException(
                        "Release candidate has no FestivalDay operating window for crowding verification."
                    );
                }
                Instant opensAt = rows.getObject(1, OffsetDateTime.class).toInstant();
                Instant closesAt = rows.getObject(2, OffsetDateTime.class).toInstant();
                return opensAt.plus(Duration.between(opensAt, closesAt).dividedBy(2));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read the candidate FestivalDay operating window.", exception);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record PreparedCandidate(long revisionNumber, Instant verificationInstant) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock releaseE2eClock() {
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

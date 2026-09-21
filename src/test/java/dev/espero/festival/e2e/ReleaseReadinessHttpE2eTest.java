package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogCliRunner;
import dev.espero.festival.cleanup.CleanupDataSourceProvider;
import dev.espero.festival.cleanup.CleanupProperties;
import dev.espero.festival.cleanup.CleanupScheduler;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
import org.springframework.context.ApplicationContext;
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
        "festival.admin-auth.bootstrap-password=release-e2e-password",
        "spring.flyway.enabled=false",
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
@Import(ReleaseReadinessHttpE2eTest.ClockConfiguration.class)
@Testcontainers
@Timeout(120)
class ReleaseReadinessHttpE2eTest {

    static final String CANDIDATE_MANIFEST_PROPERTY = "festival.release-e2e.manifest";
    static final String REQUIRE_USER_JOURNEY_CONTENT_PROPERTY = "festival.release-e2e.require-user-journey-content";

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String ADMIN_ORIGIN = "https://admin.e2e.test";
    private static final String REFRESH_COOKIE_NAME = "__Host-festival-admin-refresh";
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
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
        registry.add("spring.flyway.placeholders.festivalId", FESTIVAL_ID::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CleanupProperties cleanupProperties;

    @Autowired
    private CleanupDataSourceProvider cleanupDataSourceProvider;

    @Test
    void releaseHarnessDisablesInheritedCleanupConfiguration() {
        assertThat(cleanupProperties.hasDedicatedDataSourceAndRole()).isFalse();
        assertThat(cleanupDataSourceProvider.hasDedicatedDataSourceAndRole()).isFalse();
        assertThat(applicationContext.getBeansOfType(CleanupScheduler.class)).isEmpty();
    }

    @Test
    void candidatePublishesBeforeStartupAndPassesVisitorAndOperatorJourneys() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        String config = assertCandidateStartupJourney(candidate);
        assertPerformanceDiscoveryJourney(config, candidate);
        assertSpaceAndMapJourney(candidate);
        assertTicketAndStampJourney(candidate);
        assertAnonymousAdminRequestsAreRejected();
        assertCrowdingAndAdministratorSessionJourney(candidate);
    }

    @Test
    void publicQueriesRejectUnpublishedLocalesAndDuplicateParametersWithReleaseMetadata() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        HttpResponse<String> unpublishedLocale = send(HttpRequest.newBuilder(uri("/api/v2/config?locale=ja"))
            .GET()
            .build());
        assertThat(unpublishedLocale.statusCode()).isEqualTo(400);
        assertThat(jsonString(unpublishedLocale.body(), "$.error.code")).isEqualTo("LOCALE_NOT_READY");
        assertCandidateMeta(unpublishedLocale.body(), candidate);
        assertThat(jsonString(unpublishedLocale.body(), "$.error.message")).isNotBlank();
        assertThat(jsonValue(unpublishedLocale.body(), "$.error.details")).isEqualTo(List.of());

        HttpResponse<String> unknownLocale = send(HttpRequest.newBuilder(uri("/api/v2/config?locale=fr"))
            .GET()
            .build());
        assertThat(unknownLocale.statusCode()).isEqualTo(400);
        assertThat(jsonString(unknownLocale.body(), "$.error.code")).isEqualTo("INVALID_QUERY");
        assertCandidateMeta(unknownLocale.body(), candidate);

        HttpResponse<String> duplicateLocale = send(HttpRequest.newBuilder(
            uri("/api/v2/config?locale=ko&locale=en")
        ).GET().build());
        assertThat(duplicateLocale.statusCode()).isEqualTo(400);
        assertThat(jsonString(duplicateLocale.body(), "$.error.code")).isEqualTo("INVALID_QUERY");
        assertCandidateMeta(duplicateLocale.body(), candidate);
    }

    @Test
    void publicInputFailuresStaySafeAndDoNotBlockAnonymousCatalogNavigation() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        for (ExpectedError expected : List.of(
            new ExpectedError("/api/v2/lineup?date=not-a-date", 400, "INVALID_QUERY"),
            new ExpectedError("/api/v2/lineup?date=2099-01-01", 400, "INVALID_DATE"),
            new ExpectedError("/api/v2/lineup?category=UNKNOWN", 400, "INVALID_QUERY"),
            new ExpectedError("/api/v2/maps?mapVersion=unexpected", 400, "INVALID_QUERY"),
            new ExpectedError("/api/v2/maps/missing-release-map", 404, "NOT_FOUND"),
            new ExpectedError("/api/v2/maps/missing-release-map/pins?mapVersion=1", 404, "NOT_FOUND"),
            new ExpectedError("/api/v2/spaces/missing-release-space", 404, "NOT_FOUND"),
            new ExpectedError("/api/v2/places/missing-release-place", 404, "NOT_FOUND"),
            new ExpectedError("/api/v2/artists/missing-release-artist", 404, "NOT_FOUND"),
            new ExpectedError("/api/v2/performances/missing-release-performance", 404, "NOT_FOUND")
        )) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(expected.path())).GET().build());
            assertThat(response.statusCode()).as(expected.path()).isEqualTo(expected.status());
            assertThat(jsonString(response.body(), "$.error.code")).isEqualTo(expected.code());
            assertThat(jsonValue(response.body(), "$.error.retryable")).isEqualTo(Boolean.FALSE);
            assertCandidateMeta(response.body(), candidate);
        }

        HttpResponse<String> publicRouteWithMalformedAdminToken = send(HttpRequest.newBuilder(uri("/api/v2/config"))
            .header("Authorization", "Bearer malformed-admin-token")
            .GET()
            .build());
        assertThat(publicRouteWithMalformedAdminToken.statusCode()).isEqualTo(200);
        assertCandidateMeta(publicRouteWithMalformedAdminToken.body(), candidate);

        HttpResponse<String> recoveredNavigation = candidateResponse("/api/v2/config", candidate);
        assertThat(jsonString(recoveredNavigation.body(), "$.data.festival.id")).isEqualTo(FESTIVAL_ID.toString());
    }

    @Test
    void defaultDateAndLineupStayAlignedBeforeAndAfterTheFestivalCalendar() throws Exception {
        PreparedCandidate candidate = prepared();
        HttpResponse<String> initial = candidateResponse("/api/v2/config", candidate);
        List<String> dates = jsonStringList(initial.body(), "$.data.festival.dates[*]");
        assertThat(dates).isNotEmpty();
        LocalDate first = LocalDate.parse(dates.getFirst());
        LocalDate last = LocalDate.parse(dates.getLast());
        ZoneId festivalZone = ZoneId.of("Asia/Seoul");

        clock.set(first.atStartOfDay(festivalZone).minusSeconds(1).toInstant());
        assertDefaultDateAndLineup(candidate, first.toString());

        clock.set(last.plusDays(1).atStartOfDay(festivalZone).toInstant());
        assertDefaultDateAndLineup(candidate, last.toString());
    }

    @Test
    void publicConditionalReadsAcceptWeakAndMultiValueValidators() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        HttpResponse<String> first = send(HttpRequest.newBuilder(uri("/api/v2/ticket-guide"))
            .GET()
            .build());
        assertThat(first.statusCode()).isEqualTo(200);
        assertCandidateMeta(first.body(), candidate);
        String etag = requiredHeader(first, "ETag");
        assertThat(etag).matches("\"[0-9a-f]{64}\"");

        HttpResponse<String> revalidated = send(HttpRequest.newBuilder(uri("/api/v2/ticket-guide"))
            .header("If-None-Match", "\"stale\", W/" + etag)
            .GET()
            .build());
        assertThat(revalidated.statusCode()).isEqualTo(304);
        assertThat(revalidated.body()).isEmpty();
        assertThat(requiredHeader(revalidated, "ETag")).isEqualTo(etag);
        assertThat(requiredHeader(revalidated, "Cache-Control")).contains("private", "no-cache");
        assertThat(requiredHeader(revalidated, "X-Request-Id")).isNotEqualTo(
            requiredHeader(first, "X-Request-Id")
        );
    }

    @Test
    void adminSessionBoundaryRejectsForeignOriginsBadCredentialsAndMalformedAccessTokens() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        HttpResponse<String> foreignOrigin = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions"))
            .header("Origin", "https://evil.e2e.test")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"release-e2e-admin\",\"password\":\"release-e2e-password\"}"
            ))
            .build());
        assertThat(foreignOrigin.statusCode()).isEqualTo(403);
        // Spring CORS rejects a disallowed Origin before the API error writer;
        // the browser-facing contract is the status and absence of credentials.
        assertThat(foreignOrigin.body()).isEqualTo("Invalid CORS request");
        assertThat(foreignOrigin.headers().allValues("Set-Cookie")).isEmpty();

        HttpResponse<String> badCredentials = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions"))
            .header("Origin", ADMIN_ORIGIN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"release-e2e-admin\",\"password\":\"wrong-password\"}"
            ))
            .build());
        assertThat(badCredentials.statusCode()).isEqualTo(401);
        assertThat(jsonString(badCredentials.body(), "$.error.code"))
            .isEqualTo("ADMIN_AUTHENTICATION_FAILED");
        assertThat(badCredentials.headers().allValues("Set-Cookie")).isEmpty();

        HttpResponse<String> malformedToken = send(HttpRequest.newBuilder(uri("/api/v2/admin/me"))
            .header("Authorization", "Bearer definitely-not-a-jwt")
            .GET()
            .build());
        assertThat(malformedToken.statusCode()).isEqualTo(401);
        assertThat(jsonString(malformedToken.body(), "$.error.code")).isEqualTo("UNAUTHORIZED");
        assertThat(jsonNumber(malformedToken.body(), "$.meta.revision").longValue()).isZero();
    }

    @Test
    void releaseCliChildCannotBeRedirectedByAHostileDatasourceOverride() throws Exception {
        prepared();
        String hikariProperty = "spring.datasource.hikari.jdbc-url";
        String dataSourceClassProperty = "spring.datasource.hikari.data-source-class-name";
        String dataSourceUrlProperty = "spring.datasource.hikari.data-source-properties.URL";
        String flywayUrlProperty = "spring.flyway.url";
        String jndiProperty = "spring.datasource.jndi-name";
        String previousHikari = System.getProperty(hikariProperty);
        String previousDataSourceClass = System.getProperty(dataSourceClassProperty);
        String previousDataSourceUrl = System.getProperty(dataSourceUrlProperty);
        String previousFlywayUrl = System.getProperty(flywayUrlProperty);
        String previousJndi = System.getProperty(jndiProperty);
        System.setProperty(hikariProperty, "jdbc:unsupported:release-e2e-no-remote-access");
        System.setProperty(dataSourceClassProperty, "dev.espero.e2e.UnsupportedDataSource");
        System.setProperty(dataSourceUrlProperty, "jdbc:unsupported:release-e2e-no-remote-access");
        System.setProperty(flywayUrlProperty, "jdbc:unsupported:release-e2e-no-remote-access");
        System.setProperty(jndiProperty, "java:comp/env/jdbc/remote-release-db");
        try (Connection serverConnection = dataSource.getConnection();
             ConfigurableApplicationContext cli = startCatalogCli()) {
            assertThat(serverConnection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            try (Connection connection = cli.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            }
        } finally {
            if (previousHikari == null) {
                System.clearProperty(hikariProperty);
            } else {
                System.setProperty(hikariProperty, previousHikari);
            }
            if (previousDataSourceClass == null) {
                System.clearProperty(dataSourceClassProperty);
            } else {
                System.setProperty(dataSourceClassProperty, previousDataSourceClass);
            }
            if (previousDataSourceUrl == null) {
                System.clearProperty(dataSourceUrlProperty);
            } else {
                System.setProperty(dataSourceUrlProperty, previousDataSourceUrl);
            }
            if (previousFlywayUrl == null) {
                System.clearProperty(flywayUrlProperty);
            } else {
                System.setProperty(flywayUrlProperty, previousFlywayUrl);
            }
            if (previousJndi == null) {
                System.clearProperty(jndiProperty);
            } else {
                System.setProperty(jndiProperty, previousJndi);
            }
        }
    }

    private String assertCandidateStartupJourney(PreparedCandidate candidate) throws Exception {
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
        assertThat(jsonString(config.body(), "$.data.festival.defaultDate")).isNotBlank();
        return config.body();
    }

    private void assertPerformanceDiscoveryJourney(String config, PreparedCandidate candidate) throws Exception {
        String defaultDate = jsonString(config, "$.data.festival.defaultDate");
        HttpResponse<String> lineup = candidateResponse("/api/v2/lineup", candidate);
        assertThat(jsonString(lineup.body(), "$.data.date")).isEqualTo(defaultDate);
        assertThat(jsonString(lineup.body(), "$.data.category")).isEqualTo("ARTIST");

        HttpResponse<String> explicitLineup = candidateResponse(
            "/api/v2/lineup?date=" + encodedQuery(defaultDate) + "&category=ARTIST", candidate
        );
        assertThat(jsonValue(explicitLineup.body(), "$.data.items"))
            .isEqualTo(jsonValue(lineup.body(), "$.data.items"));

        List<String> artistIds = jsonStringList(lineup.body(), "$.data.items[*].artistId");
        List<String> performanceIds = jsonStringList(lineup.body(), "$.data.items[*].performanceId");
        assertThat(artistIds).hasSameSizeAs(performanceIds);
        assertContinuousOrder(jsonNumberList(lineup.body(), "$.data.items[*].order"));
        if (candidate.requireUserJourneyContent()) {
            assertThat(artistIds).isNotEmpty();
        }

        Map<String, String> artistBodies = new LinkedHashMap<>();
        Map<String, String> performanceBodies = new LinkedHashMap<>();
        for (int index = 0; index < artistIds.size(); index++) {
            String artistId = artistIds.get(index);
            String performanceId = performanceIds.get(index);
            String artist = artistBodies.get(artistId);
            if (artist == null) {
                artist = candidateResponse("/api/v2/artists/" + artistId, candidate).body();
                artistBodies.put(artistId, artist);
            }
            assertThat(jsonString(artist, "$.data.id")).isEqualTo(artistId);
            assertThat(jsonStringList(artist, "$.data.performances[*].id")).contains(performanceId);

            String performance = performanceBodies.get(performanceId);
            if (performance == null) {
                performance = candidateResponse("/api/v2/performances/" + performanceId, candidate).body();
                performanceBodies.put(performanceId, performance);
            }
            assertThat(jsonString(performance, "$.data.id")).isEqualTo(performanceId);
            assertThat(jsonString(performance, "$.data.date")).isEqualTo(defaultDate);
            assertThat(jsonStringList(performance, "$.data.artists[*].id")).contains(artistId);
        }

        HttpResponse<String> timetable = candidateResponse("/api/v2/timetable", candidate);
        List<String> timetablePerformanceIds = jsonStringList(timetable.body(), "$.data.items[*].id");
        assertThat(timetablePerformanceIds).containsAll(new LinkedHashSet<>(performanceIds));
        if (candidate.requireUserJourneyContent()) {
            assertThat(timetablePerformanceIds).isNotEmpty();
        }
        for (String performanceId : new LinkedHashSet<>(timetablePerformanceIds)) {
            String performance = performanceBodies.get(performanceId);
            if (performance == null) {
                performance = candidateResponse("/api/v2/performances/" + performanceId, candidate).body();
                performanceBodies.put(performanceId, performance);
            }
            assertThat(jsonString(performance, "$.data.id")).isEqualTo(performanceId);
        }
        candidateResponse("/api/v2/prohibited-items", candidate);
    }

    @Test
    void traversesEveryDeclaredDateCategoryAndExposedSpaceFilter() throws Exception {
        PreparedCandidate candidate = prepared();
        clock.set(candidate.verificationInstant());

        HttpResponse<String> config = candidateResponse("/api/v2/config", candidate);
        List<String> festivalDates = jsonStringList(config.body(), "$.data.festival.dates[*]");
        List<String> lineupCategories = List.of("ARTIST", "CONTEST");
        Map<String, String> artistBodies = new LinkedHashMap<>();
        Map<String, String> performanceBodies = new LinkedHashMap<>();

        for (String date : festivalDates) {
            for (String category : lineupCategories) {
                String path = "/api/v2/lineup?date=" + encodedQuery(date)
                    + "&category=" + encodedQuery(category);
                HttpResponse<String> first = candidateResponse(path, candidate);
                HttpResponse<String> repeated = candidateResponse(path, candidate);
                assertThat(jsonString(first.body(), "$.data.date")).isEqualTo(date);
                assertThat(jsonString(first.body(), "$.data.category")).isEqualTo(category);
                assertThat(jsonValue(repeated.body(), "$.data.items"))
                    .as("stable lineup for %s/%s", date, category)
                    .isEqualTo(jsonValue(first.body(), "$.data.items"));

                List<String> artistIds = jsonStringList(first.body(), "$.data.items[*].artistId");
                List<String> performanceIds = jsonStringList(first.body(), "$.data.items[*].performanceId");
                assertThat(artistIds).hasSameSizeAs(performanceIds);
                assertContinuousOrder(jsonNumberList(first.body(), "$.data.items[*].order"));

                for (int index = 0; index < artistIds.size(); index++) {
                    String artistId = artistIds.get(index);
                    String performanceId = performanceIds.get(index);
                    String artist = artistBodies.get(artistId);
                    if (artist == null) {
                        artist = candidateResponse("/api/v2/artists/" + artistId, candidate).body();
                        artistBodies.put(artistId, artist);
                    }
                    assertThat(jsonString(artist, "$.data.id")).isEqualTo(artistId);
                    assertThat(jsonString(artist, "$.data.category")).isEqualTo(category);
                    assertThat(jsonStringList(artist, "$.data.performances[*].id")).contains(performanceId);

                    String performance = performanceBodies.get(performanceId);
                    if (performance == null) {
                        performance = candidateResponse(
                            "/api/v2/performances/" + performanceId, candidate
                        ).body();
                        performanceBodies.put(performanceId, performance);
                    }
                    assertThat(jsonString(performance, "$.data.id")).isEqualTo(performanceId);
                    assertThat(jsonString(performance, "$.data.date")).isEqualTo(date);
                    assertThat(jsonStringList(performance, "$.data.artists[*].id")).contains(artistId);
                }
            }
        }

        HttpResponse<String> allSpaces = candidateResponse("/api/v2/spaces", candidate);
        List<Map<?, ?>> allSpaceItems = jsonObjects(allSpaces.body(), "$.data.items");
        List<String> allSpaceIds = allSpaceItems.stream()
            .map(item -> requiredString(item, "id"))
            .toList();
        assertUniqueIds(allSpaceIds, "all public spaces");

        Map<String, String> spaceBodies = new LinkedHashMap<>();
        for (Map<?, ?> item : allSpaceItems) {
            String spaceId = requiredString(item, "id");
            String category = requiredString(item, "category");
            String detail = candidateResponse("/api/v2/spaces/" + spaceId, candidate).body();
            spaceBodies.put(spaceId, detail);
            assertThat(jsonString(detail, "$.data.id")).isEqualTo(spaceId);
            assertThat(jsonString(detail, "$.data.category")).isEqualTo(category);
        }

        LinkedHashSet<String> exposedSpaceCategories = allSpaceItems.stream()
            .map(item -> requiredString(item, "category"))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String category : exposedSpaceCategories) {
            String path = "/api/v2/spaces?category=" + encodedQuery(category);
            HttpResponse<String> first = candidateResponse(path, candidate);
            HttpResponse<String> repeated = candidateResponse(path, candidate);
            assertThat(jsonValue(repeated.body(), "$.data.items"))
                .as("stable spaces filter for %s", category)
                .isEqualTo(jsonValue(first.body(), "$.data.items"));

            List<Map<?, ?>> filteredItems = jsonObjects(first.body(), "$.data.items");
            List<String> filteredIds = filteredItems.stream()
                .map(item -> requiredString(item, "id"))
                .toList();
            assertUniqueIds(filteredIds, "spaces filtered by " + category);
            assertThat(filteredItems)
                .as("spaces filtered by %s", category)
                .allSatisfy(item -> assertThat(requiredString(item, "category")).isEqualTo(category));
            List<String> expectedIds = allSpaceItems.stream()
                .filter(item -> category.equals(requiredString(item, "category")))
                .map(item -> requiredString(item, "id"))
                .toList();
            assertThat(filteredIds).containsExactlyElementsOf(expectedIds);
            for (String spaceId : filteredIds) {
                assertThat(spaceBodies).containsKey(spaceId);
            }
        }
    }

    private void assertSpaceAndMapJourney(PreparedCandidate candidate) throws Exception {
        HttpResponse<String> spaces = candidateResponse("/api/v2/spaces", candidate);
        List<String> spaceIds = jsonStringList(spaces.body(), "$.data.items[*].id");
        if (candidate.requireUserJourneyContent()) {
            assertThat(spaceIds).isNotEmpty();
        }

        Map<String, String> spaceBodies = new LinkedHashMap<>();
        List<SpaceMapTarget> spaceTargets = new java.util.ArrayList<>();
        for (String spaceId : spaceIds) {
            String detail = candidateResponse("/api/v2/spaces/" + spaceId, candidate).body();
            spaceBodies.put(spaceId, detail);
            assertThat(jsonString(detail, "$.data.id")).isEqualTo(spaceId);
            Map<?, ?> target = nullableObject(detail, "$.data.mapTarget");
            if (target != null) {
                spaceTargets.add(new SpaceMapTarget(
                    spaceId,
                    requiredString(target, "mapId"),
                    requiredString(target, "placeId"),
                    requiredString(target, "pinId"),
                    requiredString(target, "mapVersion")
                ));
            }
        }
        if (!spaceIds.isEmpty()) {
            String category = jsonString(spaces.body(), "$.data.items[0].category");
            HttpResponse<String> filtered = candidateResponse("/api/v2/spaces?category=" + encodedQuery(category), candidate);
            assertThat(jsonStringList(filtered.body(), "$.data.items[*].category")).allMatch(category::equals);
        }

        HttpResponse<String> maps = candidateResponse("/api/v2/maps", candidate);
        List<String> mapIds = jsonStringList(maps.body(), "$.data.items[*].id");
        if (candidate.requireUserJourneyContent()) {
            assertThat(mapIds).isNotEmpty();
        }
        Map<String, String> mapBodies = new LinkedHashMap<>();
        Map<String, String> pinsBodies = new LinkedHashMap<>();
        Set<String> placeIds = new LinkedHashSet<>();
        boolean hasAreaPin = false;
        for (String mapId : mapIds) {
            String detail = candidateResponse("/api/v2/maps/" + mapId, candidate).body();
            mapBodies.put(mapId, detail);
            assertThat(jsonString(detail, "$.data.id")).isEqualTo(mapId);
        }
        for (String mapId : mapIds) {
            String detail = mapBodies.get(mapId);
            String mapVersion = jsonString(detail, "$.data.version");
            String pins = candidateResponse(mapPinsPath(mapId, mapVersion), candidate).body();
            pinsBodies.put(mapId, pins);
            assertThat(jsonString(pins, "$.data.mapId")).isEqualTo(mapId);
            assertThat(jsonString(pins, "$.data.mapVersion")).isEqualTo(mapVersion);

            Set<String> filterIds = new LinkedHashSet<>(jsonStringList(pins, "$.data.filters[*].id"));
            for (Map<?, ?> pin : jsonObjects(pins, "$.data.items")) {
                String filterGroup = nullableString(pin, "filterGroup");
                if (filterGroup != null) {
                    assertThat(filterIds).contains(filterGroup);
                }
                Map<?, ?> target = requiredObject(pin, "target");
                String kind = requiredString(target, "kind");
                if (kind.equals("PLACE")) {
                    placeIds.add(requiredString(target, "placeId"));
                } else {
                    assertThat(kind).isEqualTo("AREA");
                    String areaMapId = requiredString(target, "mapId");
                    assertThat(mapBodies).containsKey(areaMapId);
                    assertThat(jsonString(mapBodies.get(areaMapId), "$.data.kind")).isEqualTo("AREA");
                    hasAreaPin = true;
                }
            }
        }
        String overviewId = nullableJsonString(maps.body(), "$.data.overviewId");
        if (overviewId != null) {
            assertThat(mapBodies).containsKey(overviewId);
        }

        Map<String, String> placeBodies = new LinkedHashMap<>();
        for (String placeId : placeIds) {
            String detail = candidateResponse("/api/v2/places/" + placeId, candidate).body();
            placeBodies.put(placeId, detail);
            assertThat(jsonString(detail, "$.data.id")).isEqualTo(placeId);
        }
        for (SpaceMapTarget target : spaceTargets) {
            assertThat(mapBodies).containsKey(target.mapId());
            assertThat(jsonString(mapBodies.get(target.mapId()), "$.data.kind")).isEqualTo("AREA");
            assertThat(jsonString(mapBodies.get(target.mapId()), "$.data.version")).isEqualTo(target.mapVersion());
            Map<?, ?> pin = findById(jsonObjects(pinsBodies.get(target.mapId()), "$.data.items"), target.pinId());
            Map<?, ?> pinTarget = requiredObject(pin, "target");
            assertThat(requiredString(pinTarget, "kind")).isEqualTo("PLACE");
            assertThat(requiredString(pinTarget, "placeId")).isEqualTo(target.placeId());
            assertThat(placeBodies).containsKey(target.placeId());
            assertThat(jsonString(placeBodies.get(target.placeId()), "$.data.spaceId")).isEqualTo(target.spaceId());
        }
        if (candidate.requireUserJourneyContent()) {
            assertThat(spaceTargets).isNotEmpty();
            assertThat(placeIds).isNotEmpty();
            assertThat(hasAreaPin).isTrue();
        }
        if (!mapIds.isEmpty()) {
            String mapId = mapIds.getFirst();
            String mapVersion = jsonString(mapBodies.get(mapId), "$.data.version");
            HttpResponse<String> stalePins = send(HttpRequest.newBuilder(uri(mapPinsPath(mapId, mapVersion + "-stale"))).GET().build());
            assertThat(stalePins.statusCode()).isEqualTo(409);
            assertThat(jsonString(stalePins.body(), "$.error.code")).isEqualTo("MAP_VERSION_MISMATCH");
            assertCandidateMeta(stalePins.body(), candidate);
            HttpResponse<String> recoveredPins = candidateResponse(mapPinsPath(mapId, mapVersion), candidate);
            assertThat(jsonString(recoveredPins.body(), "$.data.mapVersion")).isEqualTo(mapVersion);
        }
    }

    private void assertTicketAndStampJourney(PreparedCandidate candidate) throws Exception {
        HttpResponse<String> ticketGuide = candidateResponse("/api/v2/ticket-guide", candidate);
        assertThat(requiredHeader(ticketGuide, "Cache-Control")).contains("private", "no-cache");
        assertThat(jsonString(ticketGuide.body(), "$.data.status")).isEqualTo("UNCONFIGURED");
        assertThat(jsonValue(ticketGuide.body(), "$.data.account")).isNull();
        assertThat(jsonValue(ticketGuide.body(), "$.data.paymentSettingsVersion")).isNull();
        Map<?, ?> ticketTarget = nullableObject(ticketGuide.body(), "$.data.mapTarget");
        if (ticketTarget != null) {
            assertTicketMapTargetJourney(candidate, ticketTarget);
        }
        String ticketEtag = requiredHeader(ticketGuide, "ETag");
        HttpResponse<String> ticketNotModified = send(HttpRequest.newBuilder(uri("/api/v2/ticket-guide"))
            .header("If-None-Match", ticketEtag)
            .GET()
            .build());
        assertThat(ticketNotModified.statusCode()).isEqualTo(304);
        assertThat(ticketNotModified.body()).isEmpty();
        assertThat(requiredHeader(ticketNotModified, "ETag")).isEqualTo(ticketEtag);

        HttpResponse<String> stampGuide = candidateResponse("/api/v2/stamp-guide", candidate);
        assertThat(jsonString(stampGuide.body(), "$.data.title")).isNotBlank();
        assertThat(jsonString(stampGuide.body(), "$.data.reward.name")).isNotBlank();
        assertThat(jsonNumber(stampGuide.body(), "$.data.dailyLimit").intValue()).isEqualTo(4);
        assertThat(jsonString(stampGuide.body(), "$.data.timezone")).isEqualTo("Asia/Seoul");
        if (candidate.requireUserJourneyContent()) {
            assertThat(jsonStringList(stampGuide.body(), "$.data.dates")).isNotEmpty();
        }
    }

    private void assertTicketMapTargetJourney(PreparedCandidate candidate, Map<?, ?> target) throws Exception {
        String mapId = requiredString(target, "mapId");
        String mapVersion = requiredString(target, "mapVersion");
        String pinId = requiredString(target, "pinId");
        String placeId = requiredString(target, "placeId");
        String map = candidateResponse("/api/v2/maps/" + mapId, candidate).body();
        assertThat(jsonString(map, "$.data.version")).isEqualTo(mapVersion);
        String pins = candidateResponse(mapPinsPath(mapId, mapVersion), candidate).body();
        Map<?, ?> pin = findById(jsonObjects(pins, "$.data.items"), pinId);
        Map<?, ?> pinTarget = requiredObject(pin, "target");
        assertThat(requiredString(pinTarget, "kind")).isEqualTo("PLACE");
        assertThat(requiredString(pinTarget, "placeId")).isEqualTo(placeId);
        String place = candidateResponse("/api/v2/places/" + placeId, candidate).body();
        assertThat(jsonString(place, "$.data.id")).isEqualTo(placeId);
    }

    private void assertCrowdingAndAdministratorSessionJourney(PreparedCandidate candidate) throws Exception {
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

        AdminSession session = loginAndRefreshAdministrator();
        HttpResponse<String> adminRead = send(HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .header("Authorization", "Bearer " + session.accessToken())
            .header("Origin", ADMIN_ORIGIN)
            .GET()
            .build());
        assertThat(adminRead.statusCode()).isEqualTo(200);
        String initialAdminEtag = requiredHeader(adminRead, "ETag");

        HttpRequest updateRequest = adminCrowdingUpdate(
            session.accessToken(), initialAdminEtag, "release-e2e-crowding-1", "CROWDED", false
        );
        HttpResponse<String> update = send(updateRequest);
        assertThat(update.statusCode()).isEqualTo(204);
        assertThat(update.body()).isEmpty();

        HttpResponse<String> replay = send(updateRequest);
        assertThat(replay.statusCode()).isEqualTo(204);
        assertThat(replay.body()).isEmpty();
        assertThat(crowdingAuditCount()).isEqualTo(1L);

        HttpResponse<String> changedPublic = send(HttpRequest.newBuilder(uri("/api/v2/crowding")).GET().build());
        assertThat(changedPublic.statusCode()).isEqualTo(200);
        assertThat(jsonString(changedPublic.body(), "$.data.status")).isEqualTo("CROWDED");
        assertThat(requiredHeader(changedPublic, "ETag")).isNotEqualTo(publicEtag);

        HttpResponse<String> savedAdmin = send(HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .header("Authorization", "Bearer " + session.accessToken())
            .header("Origin", ADMIN_ORIGIN)
            .GET()
            .build());
        String savedEtag = requiredHeader(savedAdmin, "ETag");
        String savedUpdatedAt = jsonString(savedAdmin.body(), "$.data.updatedAt");
        clock.set(candidate.verificationInstant().plusSeconds(1));
        HttpResponse<String> sameLevel = send(adminCrowdingUpdate(
            session.accessToken(), savedEtag, "release-e2e-crowding-2", "CROWDED", false
        ));
        assertThat(sameLevel.statusCode()).isEqualTo(204);
        HttpResponse<String> unchangedAdmin = send(HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .header("Authorization", "Bearer " + session.accessToken())
            .header("Origin", ADMIN_ORIGIN)
            .GET()
            .build());
        assertThat(jsonString(unchangedAdmin.body(), "$.data.updatedAt")).isEqualTo(savedUpdatedAt);
        assertThat(crowdingAuditCount()).isEqualTo(1L);

        HttpResponse<String> staleUpdate = send(adminCrowdingUpdate(
            session.accessToken(), initialAdminEtag, "release-e2e-crowding-3", "MODERATE", false
        ));
        assertThat(staleUpdate.statusCode()).isEqualTo(409);
        assertThat(jsonString(staleUpdate.body(), "$.error.code")).isEqualTo("EDIT_CONFLICT");
        assertThat(crowdingAuditCount()).isEqualTo(1L);

        String currentEtag = requiredHeader(unchangedAdmin, "ETag");
        HttpResponse<String> unconfirmedFull = send(adminCrowdingUpdate(
            session.accessToken(), currentEtag, "release-e2e-crowding-4", "FULL", false
        ));
        assertThat(unconfirmedFull.statusCode()).isEqualTo(422);
        assertThat(jsonString(unconfirmedFull.body(), "$.error.code")).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(crowdingAuditCount()).isEqualTo(1L);

        HttpResponse<String> full = send(adminCrowdingUpdate(
            session.accessToken(), currentEtag, "release-e2e-crowding-5", "FULL", true
        ));
        assertThat(full.statusCode()).isEqualTo(204);
        HttpResponse<String> fullPublic = send(HttpRequest.newBuilder(uri("/api/v2/crowding")).GET().build());
        assertThat(jsonString(fullPublic.body(), "$.data.status")).isEqualTo("FULL");
        assertThat(crowdingAuditCount()).isEqualTo(2L);

        assertLogoutRevokesRefresh(session);
    }

    private AdminSession loginAndRefreshAdministrator() throws Exception {
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
            REFRESH_COOKIE_NAME + "=", "Secure", "HttpOnly", "SameSite=Strict"
        );
        String initialAccessToken = jsonString(login.body(), "$.data.accessToken");
        String initialRefreshCookie = refreshCookie(login);
        assertThat(initialAccessToken).isNotBlank();

        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v2/admin/me"))
            .header("Authorization", "Bearer " + initialAccessToken)
            .GET()
            .build());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(jsonString(me.body(), "$.data.username")).isEqualTo("release-e2e-admin");
        assertThat(jsonString(me.body(), "$.data.authority")).isEqualTo("ADMIN");
        assertThat(jsonValue(me.body(), "$.data.enabled")).isEqualTo(Boolean.TRUE);

        HttpResponse<String> refreshed = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions/refresh"))
            .header("Origin", ADMIN_ORIGIN)
            .header("Cookie", initialRefreshCookie)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(requiredHeader(refreshed, "Access-Control-Allow-Origin")).isEqualTo(ADMIN_ORIGIN);
        String refreshedAccessToken = jsonString(refreshed.body(), "$.data.accessToken");
        String refreshedCookie = refreshCookie(refreshed);
        assertThat(refreshedAccessToken).isNotBlank();

        HttpResponse<String> replayedRefresh = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions/refresh"))
            .header("Origin", ADMIN_ORIGIN)
            .header("Cookie", initialRefreshCookie)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
        assertThat(replayedRefresh.statusCode()).isEqualTo(401);
        assertThat(jsonString(replayedRefresh.body(), "$.error.code")).isEqualTo("ADMIN_REFRESH_TOKEN_INVALID");
        return new AdminSession(refreshedAccessToken, refreshedCookie);
    }

    private void assertLogoutRevokesRefresh(AdminSession session) throws Exception {
        HttpResponse<String> logout = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions/current"))
            .header("Authorization", "Bearer " + session.accessToken())
            .header("Origin", ADMIN_ORIGIN)
            .header("Cookie", session.refreshCookie())
            .DELETE()
            .build());
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(jsonValue(logout.body(), "$.data.loggedOut")).isEqualTo(Boolean.TRUE);
        assertThat(requiredHeader(logout, "Set-Cookie")).contains(REFRESH_COOKIE_NAME + "=", "Max-Age=0");

        HttpResponse<String> revokedRefresh = send(HttpRequest.newBuilder(uri("/api/v2/admin/sessions/refresh"))
            .header("Origin", ADMIN_ORIGIN)
            .header("Cookie", session.refreshCookie())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
        assertThat(revokedRefresh.statusCode()).isEqualTo(401);
        assertThat(jsonString(revokedRefresh.body(), "$.error.code")).isEqualTo("ADMIN_REFRESH_TOKEN_INVALID");
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

    private void assertDefaultDateAndLineup(PreparedCandidate candidate, String expectedDate) throws Exception {
        HttpResponse<String> config = candidateResponse("/api/v2/config", candidate);
        assertThat(jsonString(config.body(), "$.data.festival.defaultDate")).isEqualTo(expectedDate);
        HttpResponse<String> lineup = candidateResponse("/api/v2/lineup", candidate);
        assertThat(jsonString(lineup.body(), "$.data.date")).isEqualTo(expectedDate);
    }

    private HttpRequest adminCrowdingUpdate(
        String accessToken,
        String etag,
        String key,
        String level,
        boolean confirmFull
    ) {
        String body = confirmFull
            ? "{\"level\":\"" + level + "\",\"confirmFull\":true}"
            : "{\"level\":\"" + level + "\"}";
        return HttpRequest.newBuilder(uri("/api/v2/admin/crowding"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Origin", ADMIN_ORIGIN)
            .header("Content-Type", "application/json")
            .header("If-Match", etag)
            .header("Idempotency-Key", key)
            .PUT(HttpRequest.BodyPublishers.ofString(body))
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
        assertThat(jsonString(body, "$.meta.timezone")).isEqualTo("Asia/Seoul");
        assertThat(jsonValue(body, "$.meta.mock")).isEqualTo(Boolean.FALSE);
    }

    private static String jsonString(String body, String path) {
        return (String) JsonPath.read(body, path);
    }

    private static String nullableJsonString(String body, String path) {
        Object value = jsonValue(body, path);
        return value == null ? null : (String) value;
    }

    private static Object jsonValue(String body, String path) {
        return JsonPath.read(body, path);
    }

    private static Number jsonNumber(String body, String path) {
        return (Number) JsonPath.read(body, path);
    }

    private static List<String> jsonStringList(String body, String path) {
        return ((List<?>) JsonPath.read(body, path)).stream().map(String.class::cast).toList();
    }

    private static List<Number> jsonNumberList(String body, String path) {
        return ((List<?>) JsonPath.read(body, path)).stream().map(Number.class::cast).toList();
    }

    private static List<Map<?, ?>> jsonObjects(String body, String path) {
        List<Map<?, ?>> objects = new java.util.ArrayList<>();
        for (Object value : (List<?>) jsonValue(body, path)) {
            if (value instanceof Map<?, ?> object) {
                objects.add(object);
                continue;
            }
            throw new AssertionError("Expected JSON object at " + path);
        }
        return List.copyOf(objects);
    }

    private static Map<?, ?> nullableObject(String body, String path) {
        Object value = jsonValue(body, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> object) {
            return object;
        }
        throw new AssertionError("Expected JSON object at " + path);
    }

    private static Map<?, ?> requiredObject(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (value instanceof Map<?, ?> nested) {
            return nested;
        }
        throw new AssertionError("Expected JSON object field " + key);
    }

    private static String requiredString(Map<?, ?> object, String key) {
        String value = nullableString(object, key);
        if (value == null || value.isBlank()) {
            throw new AssertionError("Expected nonblank JSON string field " + key);
        }
        return value;
    }

    private static String nullableString(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new AssertionError("Expected JSON string field " + key);
    }

    private static Map<?, ?> findById(List<Map<?, ?>> objects, String id) {
        return objects.stream()
            .filter(object -> id.equals(object.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected JSON object id " + id));
    }

    private static void assertContinuousOrder(List<Number> orders) {
        for (int index = 0; index < orders.size(); index++) {
            assertThat(orders.get(index).intValue()).isEqualTo(index + 1);
        }
    }

    private static void assertUniqueIds(List<String> ids, String description) {
        assertThat(new LinkedHashSet<>(ids)).as(description).hasSize(ids.size());
    }

    private static String encodedQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String mapPinsPath(String mapId, String mapVersion) {
        return "/api/v2/maps/" + mapId + "/pins?mapVersion=" + encodedQuery(mapVersion);
    }

    private long crowdingAuditCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE action = 'CROWDING_UPDATED'", Long.class
        );
    }

    private String refreshCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith(REFRESH_COOKIE_NAME + "="))
            .map(value -> value.substring(0, value.indexOf(';')))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected refresh cookie on HTTP " + response.statusCode()));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        CompletableFuture<HttpResponse<String>> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return response.get(HTTP_REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            response.cancel(true);
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
                    midpointOfFirstOperatingWindow(revision),
                    requiresUserJourneyContent()
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
            ? Path.of("dev", "catalog", "frontend-mock-catalog.json")
            : Path.of(configured);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Release candidate manifest does not exist: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static boolean requiresUserJourneyContent() {
        String configuredManifest = System.getProperty(CANDIDATE_MANIFEST_PROPERTY);
        return configuredManifest == null || configuredManifest.isBlank()
            || Boolean.parseBoolean(System.getProperty(REQUIRE_USER_JOURNEY_CONTENT_PROPERTY, "false"));
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
        try (ConfigurableApplicationContext cli = startCatalogCli()) {
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

    private record PreparedCandidate(
        long revisionNumber,
        Instant verificationInstant,
        boolean requireUserJourneyContent
    ) {}

    private record SpaceMapTarget(String spaceId, String mapId, String placeId, String pinId, String mapVersion) {}

    private record AdminSession(String accessToken, String refreshCookie) {
        @Override
        public String toString() {
            return "AdminSession[accessToken=[REDACTED], refreshCookie=[REDACTED]]";
        }
    }

    private record ExpectedError(String path, int status, String code) {}

    private static ConfigurableApplicationContext startCatalogCli(String... arguments) {
        String[] options = new String[] {
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            // Command-line properties outrank inherited shell/JVM settings,
            // including Hikari and JNDI redirects, for this release gate.
            "--spring.datasource.hikari.jdbc-url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.hikari.username=" + POSTGRES.getUsername(),
            "--spring.datasource.hikari.password=" + POSTGRES.getPassword(),
            "--spring.config.import=",
            "--spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
            "--spring.flyway.enabled=false",
            "--festival.id=" + FESTIVAL_ID,
            "--spring.main.banner-mode=off"
        };
        String[] command = java.util.stream.Stream.concat(
            java.util.Arrays.stream(options), java.util.Arrays.stream(arguments)
        ).toArray(String[]::new);
        return new SpringApplicationBuilder(CatalogCliApplication.class, ReleaseCliDataSourceConfiguration.class)
            .profiles("db", "catalog-cli")
            .web(WebApplicationType.NONE)
            .run(command);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Profile("catalog-cli")
    static class ReleaseCliDataSourceConfiguration {

        @Bean(name = "dataSource")
        @Primary
        DataSource releaseCliDataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock releaseE2eClock() {
            return new MutableClock(prepared().verificationInstant());
        }

        @Bean(name = "dataSource")
        @Primary
        DataSource releaseE2eDataSource() {
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

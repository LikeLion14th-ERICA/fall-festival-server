package dev.espero.festival.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.support.PostgresTestImages;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the real workbench on 127.0.0.1 against PostgreSQL with separate
 * export and publish roles, and drives it over HTTP the way the browser UI
 * does.
 */
@Testcontainers
class CatalogWorkbenchIntegrationTest {

    private static final String FESTIVAL_ID = "ec00912b-763f-4f8f-8f57-4bdfc389ccbf";
    private static final String INITIAL_REVISION_ID = "f109dca2-8b28-4e09-8114-beebc2bd3ea2";
    private static final String OTHER_FESTIVAL_ID = "f9f0db0e-7d2c-4d58-9b84-3f06eb4d2e3a";
    private static final String OTHER_REVISION_ID = "e1b8c4a1-4c74-4f70-8a1f-2e4b6d9c7a10";
    private static final Path DEVELOPMENT_CATALOG = Path.of("dev", "catalog", "development-catalog.json");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private HttpServer backend;

    @BeforeAll
    static void prepareDatabase() throws SQLException {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", ""))
            .load()
            .migrate();
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE workbench_export LOGIN PASSWORD 'export-test-password'");
            statement.execute("GRANT USAGE ON SCHEMA public TO workbench_export");
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO workbench_export");
            statement.execute("CREATE ROLE workbench_publish LOGIN PASSWORD 'publish-test-password'");
            statement.execute("GRANT USAGE ON SCHEMA public TO workbench_publish");
            statement.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO workbench_publish"
            );
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO workbench_publish");
            statement.execute("INSERT INTO festivals (id, title, timezone, created_at, updated_at) VALUES "
                + "('" + OTHER_FESTIVAL_ID + "', 'Other festival', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO festival_revisions "
                + "(id, festival_id, revision_number, state, published_at, created_at, updated_at) VALUES "
                + "('" + OTHER_REVISION_ID + "', '" + OTHER_FESTIVAL_ID + "', 1, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @AfterEach
    void stop() {
        contexts.forEach(ConfigurableApplicationContext::close);
        contexts.clear();
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    void validatesDiffsImportsPublishesAndChecksTheBackend() throws Exception {
        AtomicLong servedRevision = new AtomicLong(1);
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/readyz", exchange -> respond(exchange, 200, "{}"));
        backend.createContext("/api/v2/maps", exchange -> respond(
            exchange, 200, "{\"meta\":{\"revision\":" + servedRevision.get() + "}}"
        ));
        backend.start();
        Workbench workbench = start(true, "http://127.0.0.1:" + backend.getAddress().getPort());

        JsonNode status = workbench.get("/api/status").body();
        assertThat(status.path("publishEnabled").asBoolean()).isTrue();
        assertThat(status.path("published").path("id").asString()).isEqualTo(INITIAL_REVISION_ID);
        assertThat(status.toString()).doesNotContain("password", "jdbc:");

        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(DEVELOPMENT_CATALOG));
        Response validation = workbench.post("/api/validate", Map.of(
            "manifest", manifest, "baselineRevisionId", INITIAL_REVISION_ID
        ));
        assertThat(validation.body().path("valid").asBoolean()).isTrue();
        assertThat(validation.body().path("baseline").path("matches").asBoolean()).isTrue();

        Response diff = workbench.post("/api/diff", Map.of("manifest", manifest));
        assertThat(diff.body().path("againstRevisionId").asString()).isEqualTo(INITIAL_REVISION_ID);
        assertThat(sectionNames(diff.body())).contains("festivalDays", "performances");

        Response imported = workbench.post("/api/import", Map.of(
            "manifest", manifest, "actor", "release operator", "baselineRevisionId", INITIAL_REVISION_ID
        ));
        assertThat(imported.status()).isEqualTo(200);
        String draft = imported.body().path("revisionId").asString();

        Response published = workbench.post("/api/publish", Map.of("revisionId", draft, "actor", "release operator"));
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.body().path("published").path("revisionNumber").asLong()).isEqualTo(2);

        Response exported = workbench.post("/api/export", Map.of("revisionId", draft));
        Response unchanged = workbench.post("/api/diff", Map.of("manifest", exported.body().path("manifest")));
        assertThat(unchanged.body().path("sections").isEmpty()).isTrue();

        Response stale = workbench.post("/api/import", Map.of(
            "manifest", manifest, "actor", "release operator", "baselineRevisionId", INITIAL_REVISION_ID
        ));
        assertThat(stale.status()).isEqualTo(422);
        assertThat(stale.body().path("message").asString()).contains("BASE_REVISION_CONFLICT");

        Response beforeRestart = workbench.post("/api/post-publish-check", Map.of());
        assertThat(beforeRestart.body().path("ready").asBoolean()).isTrue();
        assertThat(beforeRestart.body().path("revisionMatches").asBoolean()).isFalse();
        servedRevision.set(2);
        Response afterRestart = workbench.post("/api/post-publish-check", Map.of());
        assertThat(afterRestart.body().path("revisionMatches").asBoolean()).isTrue();

        Response invalidDraftResponse = workbench.post("/api/import", Map.of(
            "manifest", manifest, "actor", "release operator", "baselineRevisionId", draft
        ));
        assertThat(invalidDraftResponse.status()).isEqualTo(200);
        String invalidDraft = invalidDraftResponse.body().path("revisionId").asString();
        deletePerformanceTranslations(invalidDraft);

        Response rejected = workbench.post("/api/publish", Map.of(
            "revisionId", invalidDraft, "actor", "release operator"
        ));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().path("error").asString()).isEqualTo("CATALOG_REJECTED");
        assertThat(rejected.body().path("message").asString())
            .contains("performances")
            .doesNotContain("jdbc:", "password", "CatalogIntegrityException", "at dev.espero");
        assertThat(workbench.get("/api/status").body().path("published").path("id").asString()).isEqualTo(draft);

        assertThat(auditActors()).containsOnly("release operator");
    }

    @Test
    void reportsAnInvalidManifestWithoutWritingAndRejectsUnauthenticatedCalls() throws Exception {
        Workbench workbench = start(true, null);
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(DEVELOPMENT_CATALOG));
        manifest.put("festivalId", "00000000-0000-0000-0000-000000000001");

        Response validation = workbench.post("/api/validate", Map.of("manifest", manifest));
        assertThat(validation.body().path("valid").asBoolean()).isFalse();
        assertThat(validation.body().path("error").asString()).contains("festivalId");

        HttpResponse<String> noToken = client.send(HttpRequest.newBuilder(workbench.uri("/api/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
        HttpResponse<String> noOrigin = client.send(HttpRequest.newBuilder(workbench.uri("/api/import"))
                .header(WorkbenchRequestGuard.TOKEN_HEADER, workbench.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(noOrigin.statusCode()).isEqualTo(403);
        HttpResponse<String> page = client.send(HttpRequest.newBuilder(workbench.uri("/")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("카탈로그 워크벤치").doesNotContain(workbench.token());
    }

    @Test
    void rejectsARevisionFromAnotherFestivalBeforeExportDiffOrPublish() throws Exception {
        Workbench workbench = start(true, null);
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(DEVELOPMENT_CATALOG));
        OtherFestivalState before = otherFestivalState();

        Response exported = workbench.post("/api/export", Map.of("revisionId", OTHER_REVISION_ID));
        Response diff = workbench.post("/api/diff", Map.of(
            "manifest", manifest, "againstRevisionId", OTHER_REVISION_ID
        ));
        Response published = workbench.post("/api/publish", Map.of(
            "revisionId", OTHER_REVISION_ID, "actor", "release operator"
        ));

        for (Response response : List.of(exported, diff, published)) {
            assertThat(response.status()).isEqualTo(422);
            assertThat(response.body().path("error").asString()).isEqualTo("REVISION_FESTIVAL_MISMATCH");
        }
        assertThat(otherFestivalState()).isEqualTo(before);
    }

    @Test
    void runsExportOnlyWithoutPublishCredentials() throws Exception {
        Workbench workbench = start(false, null);
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(DEVELOPMENT_CATALOG));

        assertThat(workbench.get("/api/status").body().path("publishEnabled").asBoolean()).isFalse();
        Response imported = workbench.post("/api/import", Map.of("manifest", manifest, "actor", "release operator"));
        assertThat(imported.status()).isEqualTo(403);
        assertThat(imported.body().path("error").asString()).isEqualTo("PUBLISH_ROLE_NOT_CONFIGURED");
    }

    @Test
    void ignoresInheritedDatasourceVariantsAndConfigSources() throws Exception {
        Map<String, String> hostile = Map.ofEntries(
            Map.entry("spring.datasource.url", "jdbc:unsupported:workbench-isolation"),
            Map.entry("spring.datasource.username", "remote-user"),
            Map.entry("spring.datasource.password", "remote-password"),
            Map.entry("spring.datasource.hikari.jdbc-url", "jdbc:unsupported:workbench-hikari"),
            Map.entry("spring.datasource.hikari.username", "remote-hikari-user"),
            Map.entry("spring.datasource.hikari.password", "remote-hikari-password"),
            Map.entry("spring.datasource.hikari.data-source-class-name", "org.postgresql.ds.PGSimpleDataSource"),
            Map.entry(
                "spring.datasource.hikari.data-source-properties.URL",
                "jdbc:unsupported:workbench-data-source"
            ),
            Map.entry("spring.datasource.jndi-name", "java:comp/env/jdbc/remote-workbench"),
            Map.entry(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
            ),
            Map.entry("spring.config.location", "optional:file:/missing-workbench-config.properties"),
            Map.entry("spring.config.import", "optional:file:/missing-workbench-import.properties"),
            Map.entry("spring.profiles.active", "remote-workbench"),
            Map.entry("spring.profiles.include", "remote-workbench")
        );
        Map<String, String> previous = new HashMap<>();
        hostile.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
        hostile.forEach(System::setProperty);
        try {
            Workbench workbench = start(true, null);
            JsonNode status = workbench.get("/api/status").body();
            assertThat(status.path("published").path("id").asString()).isEqualTo(publishedRevisionId());
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    @Test
    void refusesToStartWithASharedRoleOrANonLoopbackDatabase() {
        assertThatThrownBy(() -> CatalogWorkbenchApplication.run(
            "--CATALOG_WORKBENCH_PORT=0",
            "--festival.id=" + FESTIVAL_ID,
            "--catalog.workbench.export.url=" + POSTGRES.getJdbcUrl(),
            "--catalog.workbench.export.username=workbench_export",
            "--catalog.workbench.export.password=export-test-password",
            "--catalog.workbench.publish.url=" + POSTGRES.getJdbcUrl(),
            "--catalog.workbench.publish.username=workbench_export",
            "--catalog.workbench.publish.password=export-test-password"
        )).hasStackTraceContaining("different database roles");
        assertThatThrownBy(() -> CatalogWorkbenchApplication.run(
            "--CATALOG_WORKBENCH_PORT=0",
            "--festival.id=" + FESTIVAL_ID,
            "--catalog.workbench.export.url=jdbc:postgresql://db.example.com:5432/festival",
            "--catalog.workbench.export.username=workbench_export",
            "--catalog.workbench.export.password=export-test-password"
        )).hasStackTraceContaining("loopback host");
    }

    private Workbench start(boolean publish, String backendUrl) {
        List<String> args = new ArrayList<>(List.of(
            "--CATALOG_WORKBENCH_PORT=0",
            "--festival.id=" + FESTIVAL_ID,
            "--spring.config.location=classpath:/application.yml",
            "--spring.config.import=",
            "--spring.config.additional-location=",
            "--spring.profiles.active=",
            "--spring.profiles.include=",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
            "--spring.datasource.driver-class-name=org.postgresql.Driver",
            "--spring.datasource.hikari.jdbc-url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.hikari.username=" + POSTGRES.getUsername(),
            "--spring.datasource.hikari.password=" + POSTGRES.getPassword(),
            "--spring.datasource.hikari.data-source-class-name=",
            "--spring.datasource.hikari.data-source-properties.URL=",
            "--spring.datasource.hikari.data-source-properties.url=",
            "--spring.datasource.hikari.data-source-properties.user=",
            "--spring.datasource.hikari.data-source-properties.password=",
            "--spring.datasource.jndi-name=",
            "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
            "--catalog.workbench.export.url=" + POSTGRES.getJdbcUrl(),
            "--catalog.workbench.export.username=workbench_export",
            "--catalog.workbench.export.password=export-test-password"
        ));
        if (publish) {
            args.add("--catalog.workbench.publish.url=" + POSTGRES.getJdbcUrl());
            args.add("--catalog.workbench.publish.username=workbench_publish");
            args.add("--catalog.workbench.publish.password=publish-test-password");
        }
        if (backendUrl != null) {
            args.add("--catalog.workbench.backend-url=" + backendUrl);
        }
        ConfigurableApplicationContext context = CatalogWorkbenchApplication.run(args.toArray(String[]::new));
        contexts.add(context);
        return new Workbench(
            Integer.parseInt(context.getEnvironment().getProperty("local.server.port")),
            context.getBean(WorkbenchSession.class).token()
        );
    }

    private List<String> auditActors() throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT actor FROM catalog_revision_audit")) {
            while (result.next()) {
                actors.add(result.getString(1));
            }
        }
        return actors;
    }

    private String publishedRevisionId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'published'")) {
            statement.setObject(1, java.util.UUID.fromString(FESTIVAL_ID));
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private OtherFestivalState otherFestivalState() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement revision = connection.prepareStatement(
                 "SELECT state, revision_number FROM festival_revisions WHERE id = ?");
             PreparedStatement pointer = connection.prepareStatement(
                 "SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'published'");
             PreparedStatement audits = connection.prepareStatement(
                 "SELECT COUNT(*) FROM catalog_revision_audit WHERE festival_id = ?")) {
            revision.setObject(1, java.util.UUID.fromString(OTHER_REVISION_ID));
            pointer.setObject(1, java.util.UUID.fromString(OTHER_FESTIVAL_ID));
            audits.setObject(1, java.util.UUID.fromString(OTHER_FESTIVAL_ID));
            try (var revisionRows = revision.executeQuery(); var pointerRows = pointer.executeQuery();
                 var auditRows = audits.executeQuery()) {
                revisionRows.next();
                pointerRows.next();
                auditRows.next();
                return new OtherFestivalState(
                    pointerRows.getString(1), revisionRows.getString(1), revisionRows.getLong(2), auditRows.getLong(1)
                );
            }
        }
    }

    private void deletePerformanceTranslations(String revisionId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ); PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM performance_translations WHERE festival_revision_id = ?"
        )) {
            statement.setObject(1, java.util.UUID.fromString(revisionId));
            statement.executeUpdate();
        }
    }

    private static List<String> sectionNames(JsonNode diff) {
        List<String> names = new ArrayList<>();
        diff.path("sections").forEach(section -> names.add(section.path("name").asString()));
        return names;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(int status, JsonNode body) {}

    private record OtherFestivalState(String publishedRevisionId, String state, long revisionNumber, long auditCount) {}

    private final class Workbench {

        private final int port;
        private final String token;

        Workbench(int port, String token) {
            this.port = port;
            this.token = token;
        }

        String token() {
            return token;
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        Response get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).header(WorkbenchRequestGuard.TOKEN_HEADER, token).GET());
        }

        Response post(String path, Object body) throws Exception {
            return send(HttpRequest.newBuilder(uri(path))
                .header(WorkbenchRequestGuard.TOKEN_HEADER, token)
                .header("Origin", "http://127.0.0.1:" + port)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        }

        private Response send(HttpRequest.Builder request) throws Exception {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), json.readTree(response.body()));
        }
    }
}

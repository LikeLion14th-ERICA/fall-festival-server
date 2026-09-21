package dev.espero.festival.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DatabasePreflightIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private String url;

    @BeforeEach
    void freshLocalDatabase() throws Exception {
        String database = "preflight_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.createStatement().execute("CREATE DATABASE " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void emptySchemaDoesNotRunMigrationsEvenWhenSpringEnvironmentEnablesThem() throws Exception {
        Map<String, String> environment = environment();
        environment.put("SPRING_PROFILES_ACTIVE", "db,catalog-cli");
        environment.put("SPRING_FLYWAY_ENABLED", "true");
        environment.put("ADMIN_BOOTSTRAP_USERNAME", "must-not-bootstrap");
        var output = new ByteArrayOutputStream();
        int exit = DatabasePreflightApplication.run(new String[0], environment(), DriverManager::getConnection,
            new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("READ_ONLY_DATABASE_PREFLIGHT_COMPLETE",
            "mutationAuthorized=false", "transactionReadOnly=on", "transactionIsolation=repeatable read", "relationCount=0");
        assertThat(scalar("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")).isZero();
    }

    @Test
    void verifiesActualFlywayChecksumsAndReportsExistingCatalogWithoutChangingAnyTable() throws Exception {
        migrate();
        long before = scalar("SELECT count(*) FROM flyway_schema_history");
        List<String> statements = new ArrayList<>();
        boolean[] rolledBack = {false};
        DatabasePreflight.Report report;
        try (Connection raw = connection()) {
            Connection observed = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        String sql = (String) args[0];
                        assertThat(sql.stripLeading()).startsWith("SELECT ");
                        statements.add(sql);
                    }
                    if (method.getName().equals("commit") || method.getName().equals("createStatement")) {
                        throw new AssertionError("Preflight must use bounded prepared SELECT statements and rollback.");
                    }
                    if (method.getName().equals("rollback")) {
                        rolledBack[0] = true;
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
            report = new DatabasePreflight(MigrationInventory.load()).inspect(observed, "public",
                UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf"));
            assertThat(raw.isReadOnly()).isTrue();
            assertThatThrownBy(() -> raw.createStatement().execute("CREATE TABLE forbidden_write (id integer)"))
                .isInstanceOf(SQLException.class).extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo("25006");
        }

        assertThat(report.findings()).containsExactly("EXISTING_FESTIVAL_OR_CATALOG_DATA");
        assertThat(report.migrations()).hasSize(MigrationInventory.load().migrations().size());
        assertThat(report.festivals()).hasSize(1);
        assertThat(report.revisions()).anySatisfy(revision -> assertThat(revision.get("state")).isEqualTo("published"));
        assertThat(report.catalogPresence()).containsKey("performances");
        assertThat(statements).isNotEmpty();
        assertThat(rolledBack[0]).isTrue();
        assertThat(scalar("SELECT count(*) FROM flyway_schema_history")).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM catalog_revision_audit")).isZero();
        assertThat(scalar("SELECT count(*) FROM admin_audit_events")).isZero();
        assertThat(scalar("SELECT count(*) FROM admin_accounts")).isZero();
    }

    @Test
    void stopsForChecksumMismatchUnknownTableAndOtherSchema() throws Exception {
        migrate();
        execute("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '2'");
        execute("CREATE TABLE public.other_service_private_content (secret text)");
        execute("CREATE SCHEMA unrelated_service");

        DatabasePreflight.Report report = inspect();
        assertThat(report.findings()).contains("FLYWAY_MIGRATION_MISMATCH", "UNKNOWN_SCHEMA_RELATIONS",
            "OTHER_USER_SCHEMAS_PRESENT");
        assertThat(report.catalogPresence()).doesNotContainKey("other_service_private_content");
    }

    @Test
    void stopsForUnknownHistoryAndIncompleteMigrations() throws Exception {
        migrate();
        execute("DELETE FROM flyway_schema_history WHERE version = '3'");
        execute("UPDATE flyway_schema_history SET success = false WHERE version = '2'");
        assertThat(inspect().findings()).contains("FLYWAY_FAILED_MIGRATION", "FLYWAY_MIGRATION_GAP");
    }

    @Test
    void stopsForOtherClientSessionAndMissingSelectedFestival() throws Exception {
        try (Connection otherClient = connection()) {
            var report = new DatabasePreflight(MigrationInventory.load()).inspect(otherClient, "public", UUID.randomUUID());
            assertThat(report.findings()).contains("REQUESTED_FESTIVAL_NOT_FOUND");
            try (Connection preflight = connection()) {
                var shared = new DatabasePreflight(MigrationInventory.load()).inspect(preflight, "public", null);
                assertThat(shared.findings()).contains("OTHER_DATABASE_SESSIONS_PRESENT");
            }
        }
    }

    @Test
    void standaloneMainRunsWithOnlyJdkAndPostgresqlDriverOnClasspath() throws Exception {
        Path classes = Path.of(DatabasePreflightApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path driver = Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource().getLocation().toURI());
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var processBuilder = new ProcessBuilder(executable, "-cp", classes + File.pathSeparator + driver,
            DatabasePreflightApplication.class.getName()).redirectErrorStream(true);
        processBuilder.environment().putAll(environment());
        processBuilder.environment().put("SPRING_PROFILES_ACTIVE", "db,catalog-cli");
        processBuilder.environment().put("SPRING_FLYWAY_ENABLED", "true");
        for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            processBuilder.environment().remove(name);
        }
        Process process = processBuilder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).isZero();
            assertThat(output).contains("READ_ONLY_DATABASE_PREFLIGHT_COMPLETE", "relationCount=0")
                .doesNotContain("Spring Boot", "Flyway Community", "Exception");
            assertThat(scalar("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Map<String, String> environment() {
        Map<String, String> environment = DatabasePreflightApplicationTest.environment();
        environment.put("PREFLIGHT_DATASOURCE_URL", url);
        environment.put("PREFLIGHT_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("PREFLIGHT_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        return environment;
    }

    private void migrate() {
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            // An empty festival id is the supported value for a database
            // without legacy crowding rows, which is the case here.
            .placeholders(Map.of("festivalId", ""))
            .load()
            .migrate();
    }

    private DatabasePreflight.Report inspect() throws Exception {
        try (Connection connection = connection()) {
            return new DatabasePreflight(MigrationInventory.load()).inspect(connection, "public", null);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}

package dev.espero.festival.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabasePreflightPostgresql17IntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private String url;

    @BeforeEach
    void freshLocalDatabase() throws Exception {
        String database = "preflight17_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.createStatement().execute("CREATE DATABASE " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void acceptsPostgresql17ForAnEmptyReadOnlyPreflightWithoutMutation() throws Exception {
        var output = new ByteArrayOutputStream();
        int exit = DatabasePreflightApplication.run(new String[0], environment(), DriverManager::getConnection,
            new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("READ_ONLY_DATABASE_PREFLIGHT_COMPLETE", "mutationAuthorized=false",
                "transactionReadOnly=on", "transactionIsolation=repeatable read", "relationCount=0")
            .doesNotContain("POSTGRESQL_VERSION_REQUIRES_REVIEW");
        assertThat(scalar("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")).isZero();
    }

    @Test
    void validatesLatestMigrationsReadOnlyWithoutVersionFindingOrMutation() throws Exception {
        migrate();
        long migrationCount = scalar("SELECT count(*) FROM flyway_schema_history");
        Map<String, Long> applicationTableCounts = applicationTableCounts();
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

        assertThat(report.findings()).containsExactly("EXISTING_FESTIVAL_OR_CATALOG_DATA")
            .doesNotContain("POSTGRESQL_VERSION_REQUIRES_REVIEW");
        assertThat(report.facts()).containsEntry("transactionReadOnly", "on")
            .containsEntry("transactionIsolation", "repeatable read");
        assertThat(report.facts().get("postgresqlVersion")).startsWith("17.");
        assertThat(report.migrations()).hasSize(MigrationInventory.load().migrations().size());
        assertThat(report.migrations()).last().extracting(migration -> migration.get("version")).isEqualTo("17");
        assertThat(statements).isNotEmpty();
        assertThat(rolledBack[0]).isTrue();
        assertThat(scalar("SELECT count(*) FROM flyway_schema_history")).isEqualTo(migrationCount);
        assertThat(applicationTableCounts()).isEqualTo(applicationTableCounts);
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
            .placeholders(Map.of("festivalId", ""))
            .load()
            .migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Map<String, Long> applicationTableCounts() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : MigrationInventory.load().tablesThrough(Integer.MAX_VALUE)) {
            counts.put(table, scalar("SELECT count(*) FROM \"" + table.replace("\"", "\"\"") + "\""));
        }
        return counts;
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}

package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.preflight.DatabasePreflightApplication;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ensures standalone preflight never bootstraps an empty database as a side effect. */
@Testcontainers(disabledWithoutDocker = true)
class DatabasePreflightEmptyDatabaseE2eTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void standalonePreflightCanInspectAnEmptyDatabaseTwiceWithoutCreatingApplicationState() throws Exception {
        assertNoApplicationState();

        ProcessResult first = preflight();
        assertCompletedReadOnly(first);
        assertNoApplicationState();

        ProcessResult second = preflight();
        assertCompletedReadOnly(second);
        assertNoApplicationState();
    }

    private ProcessResult preflight() throws Exception {
        Path classes = Path.of(DatabasePreflightApplication.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        Path driver = Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes + File.pathSeparator + driver,
            DatabasePreflightApplication.class.getName()
        ).directory(temporaryDirectory.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        copyOsEnvironment(inherited, environment);
        environment.put("PREFLIGHT_DATASOURCE_URL", databaseUrl());
        environment.put("PREFLIGHT_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("PREFLIGHT_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        environment.put("PREFLIGHT_SCHEMA", "public");

        Process process = builder.start();
        FutureTask<String> output = new FutureTask<>(
            () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(output);
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("preflight process must terminate").isTrue();
            return new ProcessResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void assertCompletedReadOnly(ProcessResult result) {
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains(
            "mode=READ_ONLY_DATABASE_PREFLIGHT",
            "status=READ_ONLY_DATABASE_PREFLIGHT_COMPLETE",
            "mutationAuthorized=false",
            "transactionReadOnly=on",
            "relationCount=0",
            "migrationCount=0",
            "festivalCount=0",
            "revisionCount=0"
        ).doesNotContain("Spring Boot", "Flyway");
    }

    private void assertNoApplicationState() throws SQLException {
        assertThat(scalar("""
            SELECT count(*)
            FROM pg_catalog.pg_class relation
            JOIN pg_catalog.pg_namespace schema ON schema.oid = relation.relnamespace
            WHERE schema.nspname = 'public'
              AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
            """)).isZero();
        for (String table : List.of(
            "flyway_schema_history", "festivals", "festival_revisions", "catalog_revision_audit",
            "admin_audit_events", "admin_idempotency_records")) {
            assertThat(scalar("SELECT CASE WHEN to_regclass('public." + table + "') IS NULL THEN 0 ELSE 1 END"))
                .as("%s must not be created", table).isZero();
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            databaseUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String databaseUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName();
    }

    private void copyOsEnvironment(Map<String, String> source, Map<String, String> target) {
        for (String name : List.of("ComSpec", "HOME", "HOMEDRIVE", "HOMEPATH", "LANG", "LC_ALL", "LOCALAPPDATA",
            "PATH", "PATHEXT", "Path", "SystemRoot", "TEMP", "TMP", "TMPDIR", "USERPROFILE", "WINDIR")) {
            String value = source.get(name);
            if (value != null) {
                target.put(name, value);
            }
        }
    }

    private record ProcessResult(int exitCode, String output) {}
}

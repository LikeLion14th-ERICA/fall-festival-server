package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.AccountSettingsCliApplication;
import dev.espero.festival.preflight.DatabasePreflightApplication;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Release gates for read-only operational roles in real child JVMs. */
@Testcontainers
class OperationalReleaseGateE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String READ_ONLY_PASSWORD = "release-read-only-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    private String databaseUrl;
    private String databaseName;

    @TempDir
    Path processDirectory;

    @BeforeEach
    void migrateFreshDatabase() throws SQLException {
        databaseName = "release_gate_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = POSTGRES.createConnection("" ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        databaseUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + databaseName;
        Flyway.configure()
            .dataSource(databaseUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
            .load()
            .migrate();
    }

    @Test
    void readOnlyPreflightRoleInspectsPublishedDatabaseWithoutMutation() throws Exception {
        String role = createReadOnlyRole("preflight_gate_reader");
        long historyBefore = scalar("SELECT count(*) FROM flyway_schema_history");

        ProcessResult result = preflight(Map.of(
            "PREFLIGHT_DATASOURCE_USERNAME", role,
            "PREFLIGHT_DATASOURCE_PASSWORD", READ_ONLY_PASSWORD,
            "SPRING_FLYWAY_ENABLED", "true",
            "SPRING_PROFILES_ACTIVE", "db,catalog-cli"
        ));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains(
            "mode=READ_ONLY_DATABASE_PREFLIGHT",
            "status=STOP_AND_REVIEW",
            "mutationAuthorized=false",
            "finding=EXISTING_FESTIVAL_OR_CATALOG_DATA",
            "transactionReadOnly=on",
            "currentUser=" + role,
            "sessionUser=" + role
        ).doesNotContain("Flyway", "Spring Boot", READ_ONLY_PASSWORD, databaseUrl);
        assertThat(scalar("SELECT count(*) FROM flyway_schema_history")).isEqualTo(historyBefore);
    }

    @Test
    void readOnlyAccountRoleCannotClearOrRestoreAnExistingSetting() throws Exception {
        insertConfiguredAccount();
        String role = createReadOnlyRole("account_gate_reader");

        ProcessResult clear = account(role, "clear", "--festival-id=" + FESTIVAL_ID,
            "--purpose=TICKET", "--expected-version=1", "--confirm", "--actor=e2e",
            "--reason=role-boundary", "--evidence-id=OPS-ROLE-1");
        assertThat(clear.exitCode()).isEqualTo(1);
        assertThat(clear.output()).contains("account-settings-cli: ACCOUNT_CLI_UNAVAILABLE")
            .doesNotContain(READ_ONLY_PASSWORD, databaseUrl);

        ProcessResult restore = account(role, "restore-version", "--festival-id=" + FESTIVAL_ID,
            "--purpose=TICKET", "--expected-version=1", "--source-version=1", "--last-four=1234",
            "--confirm", "--actor=e2e", "--reason=role-boundary", "--evidence-id=OPS-ROLE-2");
        assertThat(restore.exitCode()).isEqualTo(1);
        assertThat(restore.output()).contains("account-settings-cli: ACCOUNT_CLI_UNAVAILABLE")
            .doesNotContain(READ_ONLY_PASSWORD, databaseUrl);

        assertThat(scalar("SELECT count(*) FROM operational_account_settings")).isEqualTo(1);
        assertThat(text("SELECT state FROM operational_account_settings WHERE festival_id = '" + FESTIVAL_ID
            + "' AND purpose = 'TICKET'")).isEqualTo("CONFIGURED");
        assertThat(scalar("SELECT count(*) FROM operational_account_setting_history")).isEqualTo(1);
    }

    private String createReadOnlyRole(String baseName) throws SQLException {
        String role = baseName + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + READ_ONLY_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE " + databaseName + " TO " + role);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + role);
        }
        return role;
    }

    private void insertConfiguredAccount() throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO operational_account_settings
                (festival_id, purpose, state, version, bank_name, account_number, account_holder)
            VALUES (?, 'TICKET', 'CONFIGURED', 1, 'Test Bank', '12345678901234', 'Test Holder')
            """)) {
            statement.setObject(1, FESTIVAL_ID);
            statement.executeUpdate();
        }
    }

    private ProcessResult preflight(Map<String, String> overrides) throws Exception {
        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", Path.of(DatabasePreflightApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain()
                    .getCodeSource().getLocation().toURI()),
            DatabasePreflightApplication.class.getName());
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(processDirectory.toFile())
            .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        copyOsEnvironment(inherited, environment);
        environment.put("PREFLIGHT_DATASOURCE_URL", databaseUrl);
        environment.put("PREFLIGHT_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("PREFLIGHT_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        environment.put("PREFLIGHT_SCHEMA", "public");
        environment.put("PREFLIGHT_FESTIVAL_ID", FESTIVAL_ID.toString());
        environment.putAll(overrides);
        return runProcess(builder);
    }

    private ProcessResult account(String role, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dspring.main.banner-mode=off", "-Dspring.jmx.enabled=false",
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            AccountSettingsCliApplication.class.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(processDirectory.toFile())
            .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        copyOsEnvironment(inherited, environment);
        environment.put("SPRING_DATASOURCE_URL", databaseUrl);
        environment.put("SPRING_DATASOURCE_USERNAME", role);
        environment.put("SPRING_DATASOURCE_PASSWORD", READ_ONLY_PASSWORD);
        // Ignore machine-local application-db files that could replace this test datasource.
        environment.put("SPRING_CONFIG_LOCATION", "classpath:/application.yml");
        environment.put("SPRING_CONFIG_IMPORT", "");
        environment.put("SPRING_FLYWAY_ENABLED", "false");
        environment.put("FESTIVAL_ID", FESTIVAL_ID.toString());
        environment.put("LOGGING_LEVEL_ROOT", "OFF");
        return runProcess(builder);
    }

    private ProcessResult runProcess(ProcessBuilder builder) throws Exception {
        Process process = builder.start();
        FutureTask<String> output = new FutureTask<>(
            () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(output);
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
            return new ProcessResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
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

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(databaseUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private record ProcessResult(int exitCode, String output) {}
}

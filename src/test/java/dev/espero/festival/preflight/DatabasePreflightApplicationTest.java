package dev.espero.festival.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabasePreflightApplicationTest {
    @Test
    void helpDoesNotOpenDatabaseRegardlessOfSpringSettings() {
        var output = new ByteArrayOutputStream();
        int exit = DatabasePreflightApplication.run(new String[]{"--help"}, Map.of(
            "SPRING_PROFILES_ACTIVE", "db,catalog-cli", "SPRING_FLYWAY_ENABLED", "true",
            "ADMIN_BOOTSTRAP_USERNAME", "must-not-start"), (url, properties) -> {
                throw new AssertionError("Help must not open a connection.");
            }, new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("No .env, Spring profiles, Flyway");
    }

    @Test
    void doesNotFallBackToApplicationDatabaseCredentials() {
        var output = new ByteArrayOutputStream();
        int exit = DatabasePreflightApplication.run(new String[0], Map.of(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://remote.invalid/sensitive-database",
            "SPRING_DATASOURCE_USERNAME", "sensitive-user", "SPRING_DATASOURCE_PASSWORD", "secret"),
            (url, properties) -> { throw new AssertionError("Implicit credentials must not be used."); },
            new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("STOP_AND_REVIEW", "CONFIGURATION_INVALID")
            .doesNotContain("sensitive", "secret", "remote.invalid");
    }

    @Test
    void rejectsArgumentsAndUnsafeJdbcUrlOverridesBeforeConnecting() {
        for (String parameter : new String[]{"readOnly=false", "readOnlyMode=ignore", "options=-c+default_transaction_read_only=off",
                "user=other", "password=secret", "socketFactory=other.Factory", "currentSchema=unreviewed"}) {
            Map<String, String> environment = environment();
            environment.put("PREFLIGHT_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1/test?" + parameter);
            assertThatThrownBy(() -> DatabasePreflightApplication.Settings.from(environment))
                .isInstanceOf(IllegalArgumentException.class);
        }
        var output = new ByteArrayOutputStream();
        assertThat(DatabasePreflightApplication.run(new String[]{"--spring.flyway.enabled=true"}, environment(),
            (url, properties) -> { throw new AssertionError("Unexpected database connection."); },
            new PrintStream(output, true, StandardCharsets.UTF_8))).isEqualTo(2);
    }

    @Test
    void preservesTlsChoiceAndForcesBoundedReadOnlyConnectionProperties() {
        Map<String, String> environment = environment();
        environment.put("PREFLIGHT_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1/test?sslmode=verify-full&sslrootcert=ca.pem");
        var settings = DatabasePreflightApplication.Settings.from(environment);

        assertThat(settings.url()).contains("sslmode=verify-full");
        assertThat(settings.connectionProperties()).containsEntry("readOnly", "true")
            .containsEntry("readOnlyMode", "always").containsEntry("connectTimeout", "5")
            .containsEntry("socketTimeout", "15");
        assertThat(settings.connectionProperties().getProperty("options"))
            .contains("default_transaction_read_only=on", "statement_timeout=5000", "lock_timeout=1000");
        assertThat(settings.toString()).doesNotContain("test-password", "test-user", "jdbc:");
    }

    @Test
    void returnsStopWithoutPrintingSqlOrConnectionFailureDetails() {
        var output = new ByteArrayOutputStream();
        int exit = DatabasePreflightApplication.run(new String[0], environment(), (url, properties) -> {
            throw new SQLException("password=test-password jdbc:postgresql://private-host SELECT private_content", "08001");
        }, new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("STOP_AND_REVIEW", "sqlState=08001")
            .doesNotContain("test-password", "private-host", "private_content");
    }

    @Test
    void migrationInventoryIsReadFromActualBundledSql() throws Exception {
        var inventory = MigrationInventory.load();
        assertThat(inventory.byVersion()).containsKeys(1, 2, 6, 11, 12, 13);
        assertThat(inventory.tablesThrough(2)).contains("stamp_guide", "festivals", "festival_revisions", "festival_days")
            .doesNotContain("admin_accounts");
        assertThat(inventory.tablesThrough(13)).contains("catalog_revision_audit", "admin_audit_events", "performances");
    }

    static Map<String, String> environment() {
        return new HashMap<>(Map.of(
            "PREFLIGHT_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1/test",
            "PREFLIGHT_DATASOURCE_USERNAME", "test-user",
            "PREFLIGHT_DATASOURCE_PASSWORD", "test-password", "PREFLIGHT_SCHEMA", "public"));
    }
}

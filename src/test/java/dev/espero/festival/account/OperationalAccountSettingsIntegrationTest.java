package dev.espero.festival.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class OperationalAccountSettingsIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String RUNTIME_ROLE = "account_runtime_test";
    private static final String OPERATOR_ROLE = "account_operator_test";
    private static final String EXPORT_ROLE = "account_export_test";
    private static final String PUBLISH_ROLE = "account_publish_test";
    private static final String CLEANUP_ROLE = "account_cleanup_test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("festival.operational-account.transfer-link-allowed-hosts", () -> "example.test");
    }

    @Autowired
    private OperationalAccountSettingsService settings;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetAccountData() {
        jdbc.update("DELETE FROM operational_account_settings", Map.of());
        jdbc.update("DELETE FROM operational_account_setting_history", Map.of());
    }

    @AfterEach
    void dropTestRoles() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String role : roles()) {
                if (roleExists(statement, role)) {
                    statement.execute("DROP OWNED BY " + role);
                    statement.execute("DROP ROLE " + role);
                }
            }
        }
    }

    @Test
    void recordsServiceAndDirectSqlChangesInTheAppendOnlyHistory() {
        OperationalAccountChangeResult result = settings.set(
            FESTIVAL_ID,
            OperationalAccountPurpose.TICKET,
            0,
            configured("110-0000-1234"),
            "1234",
            audit()
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.setting().version()).isOne();
        assertThat(result.setting().accountLastFour()).isEqualTo("1234");
        assertThat(historyValue(1, "operation")).isEqualTo("SET");
        assertThat(historyValue(1, "db_session_user")).isEqualTo(POSTGRES.getUsername());
        assertThat(historyValue(1, "actor_display_name")).isEqualTo("release operator");
        assertThat(historyValue(1, "after_account_number")).isEqualTo("110-0000-1234");

        jdbc.update("""
            UPDATE operational_account_settings
            SET state = 'UNCONFIGURED', version = 2, bank_name = NULL, account_number = NULL,
                account_holder = NULL, transfer_link_url = NULL, updated_at = :updatedAt
            WHERE festival_id = :festivalId AND purpose = 'TICKET'
            """, new MapSqlParameterSource()
                .addValue("festivalId", FESTIVAL_ID)
                .addValue("updatedAt", OffsetDateTime.now(ZoneOffset.UTC)));

        assertThat(historyCount()).isEqualTo(2);
        assertThat(historyValue(2, "operation")).isEqualTo("DIRECT_SQL");
        assertThat(historyValue(2, "before_account_number")).isEqualTo("110-0000-1234");
        assertThat(historyValue(2, "after_state")).isEqualTo("UNCONFIGURED");
        assertThat(historyValue(2, "after_account_number")).isNull();
    }

    @Test
    void clearAndRestoreCreateNewVersionsWithoutOverwritingHistory() {
        settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, configured("110-0000-1234"), "1234", audit()
        );
        OperationalAccountChangeResult cleared = settings.clear(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 1, audit()
        );
        OperationalAccountChangeResult restored = settings.restore(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 2, 1, "1234", audit()
        );
        OperationalAccountChangeResult noChange = settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 3, configured("110-0000-1234"), "1234", audit()
        );

        assertThat(cleared.setting().state()).isEqualTo(OperationalAccountState.UNCONFIGURED);
        assertThat(cleared.setting().version()).isEqualTo(2);
        assertThat(restored.setting().state()).isEqualTo(OperationalAccountState.CONFIGURED);
        assertThat(restored.setting().version()).isEqualTo(3);
        assertThat(noChange.changed()).isFalse();
        assertThat(noChange.setting().version()).isEqualTo(3);
        assertThat(historyCount()).isEqualTo(3);
        assertThat(historyValue(1, "operation")).isEqualTo("SET");
        assertThat(historyValue(2, "operation")).isEqualTo("CLEAR");
        assertThat(historyValue(3, "operation")).isEqualTo("RESTORE");
    }

    @Test
    void preservesVersionWatermarkAndRestorableValuesAfterDirectDeletion() {
        settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, configured("110-0000-1234"), "1234", audit()
        );
        jdbc.update("DELETE FROM operational_account_settings WHERE festival_id = :festivalId AND purpose = 'TICKET'",
            new MapSqlParameterSource("festivalId", FESTIVAL_ID));

        assertThat(historyValue(OperationalAccountPurpose.TICKET, 1, "operation")).isEqualTo("DIRECT_SQL");
        assertThat(historyValue(OperationalAccountPurpose.TICKET, 1, "after_state")).isNull();

        OperationalAccountChangeResult restored = settings.restore(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, 1, "1234", audit()
        );
        assertThat(restored.setting().version()).isEqualTo(2);
        assertThat(restored.setting().state()).isEqualTo(OperationalAccountState.CONFIGURED);

        settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.GOODS, 0, configured("110-0000-5678"), "5678", audit()
        );
        jdbc.update("DELETE FROM operational_account_settings WHERE festival_id = :festivalId AND purpose = 'GOODS'",
            new MapSqlParameterSource("festivalId", FESTIVAL_ID));
        OperationalAccountChangeResult reinserted = settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.GOODS, 0, configured("110-0000-5678"), "5678", audit()
        );
        assertThat(reinserted.setting().version()).isEqualTo(2);
    }

    @Test
    void rejectsStaleExpectedVersionsAndDirectVersionJumps() {
        settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, configured("110-0000-1234"), "1234", audit()
        );

        assertThatThrownBy(() -> settings.clear(FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, audit()))
            .isInstanceOf(OperationalAccountException.class)
            .extracting(exception -> ((OperationalAccountException) exception).code())
            .isEqualTo("ACCOUNT_EXPECTED_VERSION_MISMATCH");
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE operational_account_settings
            SET version = 7, updated_at = :updatedAt
            WHERE festival_id = :festivalId AND purpose = 'TICKET'
            """, new MapSqlParameterSource()
                .addValue("festivalId", FESTIVAL_ID)
                .addValue("updatedAt", OffsetDateTime.now(ZoneOffset.UTC))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(settings.findCurrent(FESTIVAL_ID, OperationalAccountPurpose.TICKET).orElseThrow().version())
            .isOne();
    }

    @Test
    void emitsOneSafeChangeEventOnlyAfterTheAccountTransactionCommits(CapturedOutput output) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            settings.set(
                FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, configured("110-0000-1234"), "1234", audit()
            );
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        settings.previewSet(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, configured("110-0000-1234"), "1234"
        );

        settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, configured("110-0000-1234"), "1234", audit()
        );
        settings.set(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, 1, configured("110-0000-1234"), "1234", audit()
        );

        String events = output.getOut();
        assertThat(occurrences(events, "operational_account_changed")).isOne();
        assertThat(events).contains("purpose=TICKET", "version=1", "change_count=1")
            .doesNotContain("110-0000-1234", "테스트은행", "테스트예금주", "https://example.test/transfer");
    }

    @Test
    void provisioningGrantsRuntimeReadOnlyAccessAndDeniesCatalogRoles() throws Exception {
        provisionRoles();
        try (Connection connection = connection()) {
            executeAs(connection, RUNTIME_ROLE, "SELECT count(*) FROM operational_account_settings");
            assertPermissionDenied(connection, RUNTIME_ROLE, "UPDATE operational_account_settings SET version = 2");
            assertPermissionDenied(connection, EXPORT_ROLE, "SELECT count(*) FROM operational_account_settings");
            assertPermissionDenied(connection, EXPORT_ROLE, "SELECT count(*) FROM operational_account_setting_history");
            assertPermissionDenied(connection, PUBLISH_ROLE, "SELECT count(*) FROM operational_account_settings");
            assertPermissionDenied(connection, OPERATOR_ROLE,
                "UPDATE operational_account_setting_history SET operation = 'DIRECT_SQL'");
            assertPermissionDenied(connection, OPERATOR_ROLE,
                "DELETE FROM operational_account_setting_history");
            assertPermissionDenied(connection, OPERATOR_ROLE, "DELETE FROM operational_account_settings");

            executeAs(connection, OPERATOR_ROLE, """
                INSERT INTO operational_account_settings (
                    festival_id, purpose, state, version, bank_name, account_number, account_holder,
                    transfer_link_url, updated_at
                ) VALUES (
                    'ec00912b-763f-4f8f-8f57-4bdfc389ccbf', 'GOODS', 'CONFIGURED', 1,
                    '테스트은행', '110-0000-5678', '테스트예금주', NULL, CURRENT_TIMESTAMP
                )
            """);
            executeAs(connection, OPERATOR_ROLE, "SELECT count(*) FROM operational_account_setting_history");
            assertThat(scalar("""
                SELECT db_session_user
                FROM operational_account_setting_history
                WHERE festival_id = 'ec00912b-763f-4f8f-8f57-4bdfc389ccbf' AND purpose = 'GOODS'
                ORDER BY id DESC LIMIT 1
                """)).isEqualTo(POSTGRES.getUsername());
            executeAs(connection, CLEANUP_ROLE,
                "SELECT festival_id, purpose, version FROM operational_account_settings");
            assertPermissionDenied(connection, CLEANUP_ROLE, "SELECT account_number FROM operational_account_settings");
            executeAs(connection, CLEANUP_ROLE, "DELETE FROM operational_account_setting_history WHERE false");
        }
    }

    @Test
    void migrationKeepsTriggerHistoryInsideTheConfiguredFlywaySchema() throws Exception {
        String schema = "account_isolation";
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                    INSERT INTO operational_account_settings (
                        festival_id, purpose, state, version, bank_name, account_number, account_holder,
                        transfer_link_url, updated_at
                    ) VALUES (
                        'ec00912b-763f-4f8f-8f57-4bdfc389ccbf', 'TICKET', 'CONFIGURED', 1,
                        '테스트은행', '110-0000-9999', '테스트예금주', NULL, CURRENT_TIMESTAMP
                    )
                    """);
                try (var rows = statement.executeQuery("""
                    SELECT count(*) FROM operational_account_setting_history
                    WHERE purpose = 'TICKET' AND version = 1 AND after_account_number = '110-0000-9999'
                    """)) {
                    rows.next();
                    assertThat(rows.getLong(1)).isOne();
                }
            }
        } finally {
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private OperationalAccountChange configured(String accountNumber) {
        return new OperationalAccountChange(
            OperationalAccountState.CONFIGURED,
            "테스트은행",
            accountNumber,
            "테스트예금주",
            "https://example.test/transfer"
        );
    }

    private OperationalAccountAuditMetadata audit() {
        return new OperationalAccountAuditMetadata("release operator", "approved update", "OPS-2026-09-18");
    }

    private Object historyValue(long version, String column) {
        return historyValue(OperationalAccountPurpose.TICKET, version, column);
    }

    private Object historyValue(OperationalAccountPurpose purpose, long version, String column) {
        return jdbc.queryForObject(
            "SELECT " + column + " FROM operational_account_setting_history"
                + " WHERE festival_id = :festivalId AND purpose = :purpose AND version = :version ORDER BY id DESC LIMIT 1",
            new MapSqlParameterSource().addValue("festivalId", FESTIVAL_ID).addValue("purpose", purpose.name())
                .addValue("version", version),
            Object.class
        );
    }

    private long historyCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM operational_account_setting_history", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private Object scalar(String sql) {
        return jdbc.getJdbcTemplate().queryForObject(sql, Object.class);
    }

    private void provisionRoles() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String role : roles()) {
                statement.execute("CREATE ROLE " + role + " NOLOGIN");
            }
            try {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(provisioningScript().getBytes(StandardCharsets.UTF_8)));
            } catch (java.io.IOException exception) {
                throw new SQLException("Could not load account-role provisioning script", exception);
            }
        }
    }

    private String provisioningScript() throws java.io.IOException {
        String script = Files.readString(Path.of("tools", "database", "provision-operational-account-roles.sql"));
        return script
            .replace(":\"schema\"", quoted("public"))
            .replace(":\"runtime_role\"", quoted(RUNTIME_ROLE))
            .replace(":\"cleanup_role\"", quoted(CLEANUP_ROLE))
            .replace(":\"account_operator_role\"", quoted(OPERATOR_ROLE))
            .replace(":\"catalog_export_role\"", quoted(EXPORT_ROLE))
            .replace(":\"catalog_publish_role\"", quoted(PUBLISH_ROLE));
    }

    private String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void assertPermissionDenied(Connection connection, String role, String sql) {
        assertThatThrownBy(() -> executeAs(connection, role, sql))
            .isInstanceOf(SQLException.class)
            .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("42501"));
    }

    private void executeAs(Connection connection, String role, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            try {
                statement.execute(sql);
            } finally {
                statement.execute("RESET ROLE");
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String[] roles() {
        return new String[] {RUNTIME_ROLE, OPERATOR_ROLE, EXPORT_ROLE, PUBLISH_ROLE, CLEANUP_ROLE};
    }

    private boolean roleExists(Statement statement, String role) throws SQLException {
        try (var resultSet = statement.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + role + "'")) {
            return resultSet.next();
        }
    }
}

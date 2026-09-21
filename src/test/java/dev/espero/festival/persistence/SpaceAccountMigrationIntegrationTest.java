package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Upgrades a database that already holds a TICKET account to V23. The existing
 * account keeps its values and version under the empty scope, and the rebuilt
 * history trigger keeps recording changes to it.
 */
@Testcontainers(disabledWithoutDocker = true)
class SpaceAccountMigrationIntegrationTest {

    private static final String SCHEMA = "space_account_upgrade";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @Test
    void keepsAnExistingTicketAccountAndItsHistoryThroughTheUpgrade() throws SQLException {
        flyway("22").migrate();
        execute("""
            INSERT INTO operational_account_settings (
                festival_id, purpose, state, version, bank_name, account_number, account_holder, updated_at
            ) VALUES (
                'ec00912b-763f-4f8f-8f57-4bdfc389ccbf', 'TICKET', 'CONFIGURED', 1,
                '테스트은행', '110-0000-1234', '테스트예금주', CURRENT_TIMESTAMP
            )
            """);

        flyway("23").migrate();

        assertThat(text("SELECT scope_id || '|' || version || '|' || account_number FROM operational_account_settings"))
            .isEqualTo("|1|110-0000-1234");
        execute("""
            UPDATE operational_account_settings SET version = 2, account_number = '110-0000-5678'
            WHERE purpose = 'TICKET'
            """);
        assertThat(text("""
            SELECT operation || '|' || scope_id || '|' || before_account_number || '|' || after_account_number
                || '|' || after_toss_link_enabled
            FROM operational_account_setting_history WHERE version = 2
            """)).isEqualTo("DIRECT_SQL||110-0000-1234|110-0000-5678|false");
        execute("""
            INSERT INTO operational_account_settings (
                festival_id, purpose, scope_id, state, version, bank_name, account_number, account_holder,
                bank_code, toss_link_enabled, updated_at
            ) VALUES (
                'ec00912b-763f-4f8f-8f57-4bdfc389ccbf', 'SPACE', 'space-pub', 'CONFIGURED', 1,
                '예시 은행', '000123456789', '예시 예금주', 'example-bank', true, CURRENT_TIMESTAMP
            )
            """);
        assertThat(text("SELECT count(*) FROM operational_account_settings")).isEqualTo("2");
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", ""))
            .target(target)
            .load();
    }

    private String text(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        connection.setSchema(SCHEMA);
        return connection;
    }
}

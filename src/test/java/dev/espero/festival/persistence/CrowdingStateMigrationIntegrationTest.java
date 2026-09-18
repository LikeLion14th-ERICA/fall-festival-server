package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs V16 over a database whose legacy V5 crowding_state already has rows.
 * Each case uses its own schema so a failed migration cannot leak into the next.
 */
@Testcontainers(disabledWithoutDocker = true)
class CrowdingStateMigrationIntegrationTest {

    private static final String FESTIVAL_ID = "ec00912b-763f-4f8f-8f57-4bdfc389ccbf";
    private static final String REVISION_ID = "f109dca2-8b28-4e09-8114-beebc2bd3ea2";
    private static final String OTHER_FESTIVAL_ID = "0d4b2f55-7c3a-4d8e-9a61-3f2e8b1c5a70";
    private static final String DAY_ID = "7a1c3e5f-2b4d-4f6a-8c0e-1d3f5a7b9c2e";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void emptyLegacyTableMigratesWithoutAFestivalId() throws SQLException {
        String schema = "crowding_empty";
        flyway(schema, "", "16").migrate();

        assertThat(count(schema, "crowding_state_dynamic")).isZero();
    }

    @Test
    void nonEmptyLegacyTableRequiresAnExplicitFestivalId() throws SQLException {
        String schema = "crowding_missing_id";
        prepareLegacyRow(schema, "2026-09-29");

        assertThatThrownBy(() -> flyway(schema, "", "16").migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("FESTIVAL_ID is required");

        assertThat(tableExists(schema, "crowding_state_dynamic")).isFalse();
        assertThat(count(schema, "crowding_state")).isEqualTo(1);
    }

    @Test
    void refusesLegacyRowsThatBelongToAnotherFestival() throws SQLException {
        String schema = "crowding_other_festival";
        prepareLegacyRow(schema, "2026-09-29");
        execute(schema, """
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES ('%s', 'other', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.formatted(OTHER_FESTIVAL_ID));

        assertThatThrownBy(() -> flyway(schema, OTHER_FESTIVAL_ID, "16").migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("festival/day/date mismatch");

        assertThat(tableExists(schema, "crowding_state_dynamic")).isFalse();
    }

    @Test
    void refusesALegacyRowWhoseDateDisagreesWithItsFestivalDay() throws SQLException {
        String schema = "crowding_date_mismatch";
        prepareLegacyRow(schema, "2026-09-30");

        assertThatThrownBy(() -> flyway(schema, FESTIVAL_ID, "16").migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("festival/day/date mismatch");

        assertThat(tableExists(schema, "crowding_state_dynamic")).isFalse();
    }

    @Test
    void copiesMatchingLegacyRowsAndKeepsTheLegacyTable() throws SQLException {
        String schema = "crowding_copy";
        prepareLegacyRow(schema, "2026-09-29");

        flyway(schema, FESTIVAL_ID, "16").migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                 "SELECT festival_id::text, operating_date::text, level FROM crowding_state_dynamic"
             )) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(FESTIVAL_ID);
            assertThat(row.getString(2)).isEqualTo("2026-09-29");
            assertThat(row.getString(3)).isEqualTo("CROWDED");
            assertThat(row.next()).isFalse();
        }
        assertThat(count(schema, "crowding_state")).isEqualTo(1);
    }

    /** Migrates to V15 and inserts one legacy row linked to a 2026-09-29 festival day. */
    private void prepareLegacyRow(String schema, String legacyOperatingDay) throws SQLException {
        flyway(schema, "", "15").migrate();
        execute(schema, """
            INSERT INTO festival_days (
                id, festival_revision_id, festival_date, opens_at, closes_at, created_at, updated_at
            ) VALUES (
                '%s', '%s', DATE '2026-09-29',
                TIMESTAMPTZ '2026-09-29T09:00:00+09:00', TIMESTAMPTZ '2026-09-29T23:00:00+09:00',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.formatted(DAY_ID, REVISION_ID));
        execute(schema, """
            INSERT INTO crowding_state (operating_day, festival_day_id, level, updated_at)
            VALUES (DATE '%s', '%s', 'CROWDED', TIMESTAMPTZ '2026-09-29T12:00:00+09:00')
            """.formatted(legacyOperatingDay, DAY_ID));
    }

    private Flyway flyway(String schema, String festivalId, String target) {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", festivalId))
            .target(target)
            .load();
    }

    private long count(String schema, String table) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private boolean tableExists(String schema, String table) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT to_regclass('" + schema + "." + table + "') IS NOT NULL"
             )) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private void execute(String schema, String sql) throws SQLException {
        try (Connection connection = connection(schema); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        connection.setSchema(schema);
        return connection;
    }
}

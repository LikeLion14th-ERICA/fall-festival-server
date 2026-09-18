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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs V19 over pins that still carry the earlier broad filter groups. They
 * have no one-to-one mapping to the design filters, so the migration clears
 * them instead of guessing and only the design groups are accepted afterwards.
 */
@Testcontainers(disabledWithoutDocker = true)
class DesignMapFilterMigrationIntegrationTest {

    private static final String SCHEMA = "design_map_filters";
    private static final String REVISION_ID = "f109dca2-8b28-4e09-8114-beebc2bd3ea2";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void clearsTheEarlierGroupsAndAcceptsOnlyTheDesignFilters() throws SQLException {
        flyway("18").migrate();
        execute("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES ('%1$s', 'map-overview', 'OVERVIEW', 1, 'v1');
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES ('%1$s', 'map-overview', 'v1', '/assets/overview.png', 'overview', 800, 600);
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            VALUES ('%1$s', 'place-restroom', 'FACILITY', NULL);
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, filter_group, x, y, place_id, area_id
            ) VALUES ('%1$s', 'map-overview', 'v1', 'pin-restroom', 'toilet', 'CONVENIENCE', 0.5, 0.5,
                'place-restroom', NULL);
            INSERT INTO map_pin_filter_group_translations (festival_revision_id, filter_group, locale, label)
            VALUES ('%1$s', 'CONVENIENCE', 'ko', '편의 시설');
            """.formatted(REVISION_ID));

        flyway("19").migrate();

        assertThat(scalar("SELECT count(*) FROM map_pins WHERE filter_group IS NOT NULL")).isZero();
        assertThat(scalar("SELECT count(*) FROM map_pin_filter_group_translations")).isZero();
        assertThat(scalar("SELECT count(*) FROM map_pins")).isOne();

        execute("UPDATE map_pins SET filter_group = 'RESTROOM' WHERE id = 'pin-restroom'");
        execute("""
            INSERT INTO map_pin_filter_group_translations (festival_revision_id, filter_group, locale, label)
            VALUES ('%s', 'RESTROOM', 'ko', '화장실')
            """.formatted(REVISION_ID));
        assertThatThrownBy(() -> execute("UPDATE map_pins SET filter_group = 'CONVENIENCE'"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("ck_map_pins_filter_group");
        assertThatThrownBy(() -> execute("""
            INSERT INTO map_pin_filter_group_translations (festival_revision_id, filter_group, locale, label)
            VALUES ('%s', 'EXPERIENCE', 'ko', '체험')
            """.formatted(REVISION_ID)))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("ck_map_pin_filter_group_translations_group");
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

    private long scalar(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
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

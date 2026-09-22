package dev.espero.festival.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class OperationalAccountHistoryCleanupIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("festival.cleanup.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("festival.cleanup.datasource.username", POSTGRES::getUsername);
        registry.add("festival.cleanup.datasource.password", POSTGRES::getPassword);
        registry.add("festival.cleanup.datasource.role", POSTGRES::getUsername);
    }

    @Autowired
    private CleanupJob job;

    @Autowired
    private CleanupProperties properties;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        properties.getDatasource().setRole(POSTGRES.getUsername());
        jdbc.update("DELETE FROM operational_account_settings", Map.of());
        jdbc.update("DELETE FROM operational_account_setting_history", Map.of());
    }

    @Test
    void deletesOnlyOldHistoryInFiveHundredRowBatchesAndRetainsRestoreAndVersionWatermarks() {
        Instant old = Instant.now().minus(java.time.Duration.ofDays(366));
        insertHistory(501, old, "CONFIGURED");
        insertDirectDeleteWatermark(502, old);

        CleanupRunResult dryRun = job.run(CleanupMode.DRY_RUN);
        assertThat(target(dryRun)).satisfies(result -> {
            assertThat(result.eligibleCount()).isEqualTo(500);
            assertThat(result.deletedCount()).isZero();
            assertThat(result.batches()).isEqualTo(1);
        });
        assertThat(historyCount()).isEqualTo(502);

        CleanupRunResult deleted = job.run(CleanupMode.DELETE);
        assertThat(target(deleted)).satisfies(result -> {
            assertThat(result.eligibleCount()).isEqualTo(500);
            assertThat(result.deletedCount()).isEqualTo(500);
            assertThat(result.batches()).isEqualTo(1);
        });
        assertThat(historyCount()).isEqualTo(2);
        assertThat(historyCount("CONFIGURED")).isOne();
        assertThat(historyCount(null)).isOne();
        assertThat(latestVersion()).isEqualTo(502);
    }

    private void insertHistory(int count, Instant occurredAt, String afterState) {
        MapSqlParameterSource[] rows = new MapSqlParameterSource[count];
        for (int index = 1; index <= count; index++) {
            rows[index - 1] = historyRow(index, occurredAt, afterState);
        }
        jdbc.batchUpdate("""
            INSERT INTO operational_account_setting_history (
                festival_id, purpose, version, operation, before_state, before_bank_name, before_account_number,
                before_account_holder, before_transfer_link_url, after_state, after_bank_name, after_account_number,
                after_account_holder, after_transfer_link_url, db_session_user, actor_display_name, reason, evidence_id,
                occurred_at
            ) VALUES (
                :festivalId, 'TICKET', :version, 'DIRECT_SQL', NULL, NULL, NULL, NULL, NULL, :afterState,
                NULL, NULL, NULL, NULL, 'cleanup-test', NULL, NULL, NULL, :occurredAt
            )
            """, rows);
    }

    private void insertDirectDeleteWatermark(long version, Instant occurredAt) {
        jdbc.update("""
            INSERT INTO operational_account_setting_history (
                festival_id, purpose, version, operation, before_state, before_bank_name, before_account_number,
                before_account_holder, before_transfer_link_url, after_state, after_bank_name, after_account_number,
                after_account_holder, after_transfer_link_url, db_session_user, actor_display_name, reason, evidence_id,
                occurred_at
            ) VALUES (
                :festivalId, 'TICKET', :version, 'DIRECT_SQL', 'CONFIGURED', NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, 'cleanup-test', NULL, NULL, NULL, :occurredAt
            )
            """, new MapSqlParameterSource()
            .addValue("festivalId", FESTIVAL_ID)
            .addValue("version", version)
            .addValue("occurredAt", atUtc(occurredAt)));
    }

    private MapSqlParameterSource historyRow(long version, Instant occurredAt, String afterState) {
        return new MapSqlParameterSource()
            .addValue("festivalId", FESTIVAL_ID)
            .addValue("version", version)
            .addValue("afterState", afterState)
            .addValue("occurredAt", atUtc(occurredAt));
    }

    private CleanupTargetResult target(CleanupRunResult result) {
        return result.targets().stream()
            .filter(value -> value.target().equals("operational_account_setting_history"))
            .findFirst()
            .orElseThrow();
    }

    private long historyCount() {
        Long value = jdbc.queryForObject(
            "SELECT count(*) FROM operational_account_setting_history", Map.of(), Long.class
        );
        return value == null ? 0 : value;
    }

    private long historyCount(String afterState) {
        Long value = jdbc.queryForObject(
            "SELECT count(*) FROM operational_account_setting_history WHERE after_state IS NOT DISTINCT FROM :afterState",
            new MapSqlParameterSource("afterState", afterState),
            Long.class
        );
        return value == null ? 0 : value;
    }

    private long latestVersion() {
        Long version = jdbc.queryForObject(
            "SELECT max(version) FROM operational_account_setting_history", Map.of(), Long.class
        );
        return version == null ? 0 : version;
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

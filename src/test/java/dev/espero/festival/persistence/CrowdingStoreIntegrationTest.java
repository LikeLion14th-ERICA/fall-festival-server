package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.domain.CrowdingSchedule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real V5 and provisional dynamic crowding migrations against an
 * ephemeral Postgres. The legacy table remains empty by design, while the
 * revision-independent table is the authoritative read path.
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class CrowdingStoreIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CrowdingStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void resetCrowdingRows() {
        jdbc.getJdbcTemplate().update("DELETE FROM crowding_state_dynamic");
        jdbc.getJdbcTemplate().update(
            "DELETE FROM festival_days WHERE festival_revision_id = ?",
            REVISION_ID
        );
    }

    @Test
    void migrationLeavesTheTableEmpty() {
        Optional<CrowdingRecord> record = store.findFor(FESTIVAL_ID, LocalDate.parse("2030-10-01"));

        assertThat(record).isEmpty();
        Integer dynamicRows = jdbc.getJdbcTemplate().queryForObject(
            "SELECT count(*) FROM crowding_state_dynamic",
            Integer.class
        );
        assertThat(dynamicRows).isZero();
    }

    @Test
    void readsBackAManuallyInsertedRow() {
        jdbc.update(
            "INSERT INTO crowding_state_dynamic (festival_id, operating_date, level, updated_at) "
                + "VALUES (:festivalId, :day, :level, :updatedAt)",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("festivalId", FESTIVAL_ID)
                .addValue("day", LocalDate.parse("2030-10-02"))
                .addValue("level", "CROWDED")
                .addValue("updatedAt", java.sql.Timestamp.from(Instant.parse("2030-10-02T08:00:00Z")))
        );

        Optional<CrowdingRecord> record = store.findFor(FESTIVAL_ID, LocalDate.parse("2030-10-02"));

        assertThat(record).isPresent();
        assertThat(record.get().level()).isEqualTo("CROWDED");
        assertThat(record.get().updatedAt()).isEqualTo(Instant.parse("2030-10-02T08:00:00Z"));
    }

    @Test
    void sameLevelSaveIsAReadOnlyNoOp() {
        LocalDate day = LocalDate.parse("2030-10-03");
        Instant originalUpdatedAt = Instant.parse("2030-10-03T08:00:00Z");
        jdbc.update(
            "INSERT INTO crowding_state_dynamic (festival_id, operating_date, level, updated_at) "
                + "VALUES (:festivalId, :day, :level, :updatedAt)",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("festivalId", FESTIVAL_ID)
                .addValue("day", day)
                .addValue("level", "MODERATE")
                .addValue("updatedAt", java.sql.Timestamp.from(originalUpdatedAt))
        );

        CrowdingStore.CrowdingMutation mutation = store.save(
            FESTIVAL_ID,
            day,
            "MODERATE",
            Instant.parse("2030-10-03T09:00:00Z")
        );

        assertThat(mutation.changed()).isFalse();
        assertThat(mutation.record().updatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void readsPublishedFestivalDaySchedulesInDateOrder() {
        jdbc.update("""
            INSERT INTO festival_days (
                id, festival_revision_id, festival_date, opens_at, closes_at, created_at, updated_at
            ) VALUES (:id, :revisionId, :firstDate, :firstOpen, :firstClose, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                     (:secondId, :revisionId, :secondDate, :secondOpen, :secondClose, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("secondId", UUID.randomUUID())
            .addValue("revisionId", REVISION_ID)
            .addValue("firstDate", LocalDate.parse("2030-10-01"))
            .addValue("secondDate", LocalDate.parse("2030-10-03"))
            .addValue("firstOpen", java.time.OffsetDateTime.parse("2030-10-01T13:00:00+09:00"))
            .addValue("firstClose", java.time.OffsetDateTime.parse("2030-10-01T22:00:00+09:00"))
            .addValue("secondOpen", java.time.OffsetDateTime.parse("2030-10-03T12:00:00+09:00"))
            .addValue("secondClose", java.time.OffsetDateTime.parse("2030-10-03T21:00:00+09:00"))
        );

        List<CrowdingSchedule> schedules = store.findSchedules(REVISION_ID);

        assertThat(schedules).extracting(CrowdingSchedule::operatingDate)
            .containsExactly(LocalDate.parse("2030-10-01"), LocalDate.parse("2030-10-03"));
        assertThat(schedules.getFirst().opensAt().toInstant())
            .isEqualTo(Instant.parse("2030-10-01T04:00:00Z"));
    }
}

package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.domain.CrowdingRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
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
 * Runs the real V5__create_crowding_state migration against an ephemeral
 * Postgres. crowding_state starts empty by design (KST 00:00 reset means "no
 * row" rather than a carried-over value), so this verifies both the empty
 * case and a manually inserted row read back correctly.
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class CrowdingStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @Test
    void migrationLeavesTheTableEmpty() {
        Optional<CrowdingRecord> record = store.findFor(LocalDate.parse("2030-10-01"));

        assertThat(record).isEmpty();
    }

    @Test
    void readsBackAManuallyInsertedRow() {
        jdbc.update(
            "INSERT INTO crowding_state (operating_day, level, updated_at) VALUES (:day, :level, :updatedAt)",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("day", LocalDate.parse("2030-10-02"))
                .addValue("level", "CROWDED")
                .addValue("updatedAt", java.sql.Timestamp.from(Instant.parse("2030-10-02T08:00:00Z")))
        );

        Optional<CrowdingRecord> record = store.findFor(LocalDate.parse("2030-10-02"));

        assertThat(record).isPresent();
        assertThat(record.get().level()).isEqualTo("CROWDED");
        assertThat(record.get().updatedAt()).isEqualTo(Instant.parse("2030-10-02T08:00:00Z"));
    }
}

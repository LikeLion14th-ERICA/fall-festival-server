package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.domain.TicketGuideConfig;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the real V1-V9 Flyway chain and revision-scoped ticket guide mapping. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class TicketGuideStoreIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    private static final UUID OTHER_REVISION_ID = UUID.fromString("e822e93f-f13d-4994-af15-e41722cccf4c");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TicketGuideStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void migrationBackfillsRevisionAndMapsTheSeededGuide() {
        Optional<TicketGuideConfig> guide = store.find(REVISION_ID);
        UUID storedRevisionId = jdbc.queryForObject(
            "SELECT festival_revision_id FROM ticket_guide WHERE id = 1", Map.of(), UUID.class
        );
        String nullable = jdbc.queryForObject("""
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'ticket_guide'
              AND column_name = 'festival_revision_id'
            """, Map.of(), String.class);

        assertThat(guide).isPresent();
        assertThat(storedRevisionId).isEqualTo(REVISION_ID);
        assertThat(nullable).isEqualTo("NO");
        TicketGuideConfig config = guide.get();
        assertThat(config.unitPriceAmount()).isEqualTo(15000);
        assertThat(config.dailyTransferOpenTime()).isEqualTo(LocalTime.of(0, 0));
        assertThat(config.dailyTransferCloseTime()).isEqualTo(LocalTime.of(21, 0));
        assertThat(config.dailyPickupOpenTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(config.dailyPickupCloseTime()).isEqualTo(LocalTime.of(21, 0));
        assertThat(config.instructions()).hasSize(2);
        assertThat(config.festivalStartDate()).isNull();
        assertThat(config.festivalEndDate()).isNull();
        assertThat(config.accountBankName()).isNull();
        assertThat(config.hasSchedule()).isFalse();
        assertThat(config.hasAccount()).isFalse();
        assertThat(config.updatedAt()).isNotNull();
    }

    @Test
    @Transactional
    void doesNotReturnGuideForAnotherRevision() {
        jdbc.update("""
            INSERT INTO festival_revisions (
                id, festival_id, revision_number, state,
                approved_at, scheduled_at, published_at, created_at, updated_at
            ) VALUES (
                :id, :festivalId, 2, 'archived',
                NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """, Map.of("id", OTHER_REVISION_ID, "festivalId", FESTIVAL_ID));

        assertThat(store.find(OTHER_REVISION_ID)).isEmpty();
    }
}

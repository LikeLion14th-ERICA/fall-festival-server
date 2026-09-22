package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

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
    void migrationKeepsLegacyPlaceholderButNormalizesRevisionCopyToUnconfigured() {
        UUID publishedRevisionId = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'", Map.of(), UUID.class
        );
        assertThat(jdbc.queryForObject(
            "SELECT daily_transfer_open_time FROM ticket_guide WHERE id = 1", Map.of(), LocalTime.class
        )).isEqualTo(LocalTime.of(0, 0));
        Optional<TicketGuideConfig> guide = store.find(publishedRevisionId);

        assertThat(guide).isPresent();
        assertThat(guide.get().dailyTransferOpenTime()).isNull();
        assertThat(guide.get().dailyTransferCloseTime()).isNull();
        assertThat(guide.get().dailyPickupOpenTime()).isNull();
        assertThat(guide.get().dailyPickupCloseTime()).isNull();
        assertThat(guide.get().hasSchedule()).isFalse();
    }

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
        assertThat(config.dailyTransferOpenTime()).isNull();
        assertThat(config.dailyTransferCloseTime()).isNull();
        assertThat(config.dailyPickupOpenTime()).isNull();
        assertThat(config.dailyPickupCloseTime()).isNull();
        assertThat(config.instructions()).hasSize(2);
        assertThat(config.festivalStartDate()).isNull();
        assertThat(config.festivalEndDate()).isNull();
        assertThat(config.hasSchedule()).isFalse();
        assertThat(config.updatedAt()).isNotNull();
    }

    @Test
    @Transactional
    void revisionScopedValuesTakePrecedenceOverTheLegacySingletonMirror() {
        UUID publishedRevisionId = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'", Map.of(), UUID.class
        );
        jdbc.update("""
            UPDATE ticket_guide
            SET unit_price_amount = 9999, account_bank_name = '오래된 은행',
                account_number = '000-0000', account_holder = '오래된 예금주'
            WHERE id = 1
            """, Map.of());

        Optional<TicketGuideConfig> guide = store.find(publishedRevisionId);

        assertThat(guide).isPresent();
        assertThat(guide.get().unitPriceAmount()).isEqualTo(15000);
    }

    /**
     * The legacy account columns stay in the table for history. A revision read
     * must never select them, so filling them in changes nothing that the
     * public ticket guide can serve.
     */
    @Test
    @Transactional
    void ignoresLegacyAccountColumnsOnTheRevisionRow() {
        UUID publishedRevisionId = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'", Map.of(), UUID.class
        );
        jdbc.update("""
            UPDATE ticket_guide_revisions
            SET account_bank_name = '레거시 은행', account_number = '000-0000',
                account_holder = '레거시 예금주', transfer_link_label = '송금',
                transfer_link_url = 'https://toss.example.invalid/send'
            WHERE festival_revision_id = :revisionId AND id = 1
            """, Map.of("revisionId", publishedRevisionId));

        Optional<TicketGuideConfig> guide = store.find(publishedRevisionId);

        assertThat(guide).isPresent();
        assertThat(guide.get().unitPriceAmount()).isEqualTo(15000);
        assertThat(TicketGuideConfig.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("accountBankName", "accountNumber", "accountHolder", "transferLinkUrl");
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

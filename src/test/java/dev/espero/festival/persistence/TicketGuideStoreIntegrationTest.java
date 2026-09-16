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

/**
 * Runs the real V2__create_ticket_guide migration against an ephemeral
 * Postgres and reads it back through TicketGuideStore, closing the gap the
 * mocked TicketGuideControllerTest can't cover: that the nullable
 * DATE/TIME/INTEGER columns and the TEXT[] instructions column actually map
 * to TicketGuideConfig correctly.
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class TicketGuideStoreIntegrationTest {

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
    void migrationKeepsLegacyPlaceholderButNormalizesRevisionCopyToUnconfigured() {
        UUID publishedRevisionId = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'", Map.of(), UUID.class
        );
        assertThat(jdbc.queryForObject(
            "SELECT daily_transfer_open_time FROM ticket_guide WHERE id = 1", Map.of(), LocalTime.class
        )).isEqualTo(LocalTime.of(0, 0));
        Optional<TicketGuideConfig> guide = store.find(publishedRevisionId);

        assertThat(guide).isPresent();
        TicketGuideConfig config = guide.get();
        assertThat(config.unitPriceAmount()).isEqualTo(15000);
        assertThat(config.dailyTransferOpenTime()).isNull();
        assertThat(config.dailyTransferCloseTime()).isNull();
        assertThat(config.dailyPickupOpenTime()).isNull();
        assertThat(config.dailyPickupCloseTime()).isNull();
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
        assertThat(guide.get().accountBankName()).isNull();
    }
}

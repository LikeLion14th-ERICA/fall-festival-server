package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.domain.TicketGuideConfig;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    @Test
    void migrationSeedsThePriceAndScheduleButLeavesDatesAndAccountUnset() {
        Optional<TicketGuideConfig> guide = store.find();

        assertThat(guide).isPresent();
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
        assertThat(config.hasMapTarget()).isFalse();
        assertThat(config.updatedAt()).isNotNull();
    }
}

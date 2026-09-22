package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.CatalogRevisionService;
import dev.espero.festival.account.OperationalAccountAuditMetadata;
import dev.espero.festival.account.OperationalAccountChange;
import dev.espero.festival.account.OperationalAccountPurpose;
import dev.espero.festival.account.OperationalAccountSettingsService;
import dev.espero.festival.account.OperationalAccountState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Serves the public ticket guide from a published catalog and the separate
 * operational account setting. An account change is visible on the very next
 * request without a catalog publish or restart, which is well inside the
 * frontend's 15 second polling interval.
 */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class TicketGuideAccountFlowIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final Path DEVELOPMENT_CATALOG = Path.of("dev", "catalog", "development-catalog.json");
    private static final String ROUTE = "/api/v2/ticket-guide";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private CatalogRevisionService revisions;

    @Autowired
    private CatalogSnapshotProvider snapshots;

    @Autowired
    private OperationalAccountSettingsService accounts;

    @Autowired
    private MutableClock clock;

    @TempDir
    private Path tempDir;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        clock.set(OffsetDateTime.parse("2026-09-29T12:00:00+09:00"));
        jdbc.update("DELETE FROM operational_account_settings", Map.of());
        jdbc.update("DELETE FROM operational_account_setting_history", Map.of());
        publishScheduledTicketGuide();
    }

    @Test
    void reflectsEachAccountChangeOnTheNextRequestWithANewEtag() throws Exception {
        String unconfigured = ticketGuide()
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-cache"))
            .andExpect(jsonPath("$.data.status").value("UNCONFIGURED"))
            .andExpect(jsonPath("$.data.account").doesNotExist())
            .andExpect(jsonPath("$.data.paymentSettingsVersion").doesNotExist())
            .andReturn().getResponse().getHeader("ETag");

        accounts.set(FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, account("110-0000-1234"), "1234", audit());
        Instant changedAt = clock.instant();

        String first = ticketGuide()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("TRANSFER_OPEN"))
            .andExpect(jsonPath("$.data.account.accountNumber").value("110-0000-1234"))
            .andExpect(jsonPath("$.data.paymentSettingsVersion").value(1))
            .andExpect(jsonPath("$.data.transferLink").doesNotExist())
            .andReturn().getResponse().getHeader("ETag");
        assertThat(first).isNotEqualTo(unconfigured);

        // A proxy revalidating with the old tag gets the new body; with the new tag, a 304.
        conditionalTicketGuide(unconfigured).andExpect(status().isOk());
        conditionalTicketGuide(first)
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", first))
            .andExpect(header().string("Cache-Control", "private, no-cache"));

        accounts.set(FESTIVAL_ID, OperationalAccountPurpose.TICKET, 1, account("110-0000-5678"), "5678", audit());

        String second = ticketGuide()
            .andExpect(jsonPath("$.data.account.accountNumber").value("110-0000-5678"))
            .andExpect(jsonPath("$.data.paymentSettingsVersion").value(2))
            .andReturn().getResponse().getHeader("ETag");
        assertThat(second).isNotEqualTo(first);
        conditionalTicketGuide(first).andExpect(status().isOk());
        assertThat(clock.instant()).isEqualTo(changedAt);

        accounts.clear(FESTIVAL_ID, OperationalAccountPurpose.TICKET, 2, audit());
        ticketGuide()
            .andExpect(jsonPath("$.data.status").value("UNCONFIGURED"))
            .andExpect(jsonPath("$.data.account").doesNotExist())
            .andExpect(jsonPath("$.data.paymentSettingsVersion").value(3));
    }

    @Test
    void hidesTheAccountAndChangesTheEtagAtTheDailyTransferClose() throws Exception {
        accounts.set(FESTIVAL_ID, OperationalAccountPurpose.TICKET, 0, account("110-0000-1234"), "1234", audit());
        clock.set(OffsetDateTime.parse("2026-09-29T17:59:59+09:00"));
        String open = ticketGuide()
            .andExpect(jsonPath("$.data.status").value("TRANSFER_OPEN"))
            .andReturn().getResponse().getHeader("ETag");

        clock.set(OffsetDateTime.parse("2026-09-29T18:00:00+09:00"));
        String closed = ticketGuide()
            .andExpect(jsonPath("$.data.status").value("DAILY_CLOSED"))
            .andExpect(jsonPath("$.data.account").doesNotExist())
            .andReturn().getResponse().getHeader("ETag");

        assertThat(closed).isNotEqualTo(open);
        conditionalTicketGuide(open).andExpect(status().isOk());
    }

    private ResultActions ticketGuide() throws Exception {
        return mvc.perform(get(ROUTE));
    }

    private ResultActions conditionalTicketGuide(String etag) throws Exception {
        return mvc.perform(get(ROUTE).header("If-None-Match", etag));
    }

    private void publishScheduledTicketGuide() throws Exception {
        String manifest = Files.readString(DEVELOPMENT_CATALOG)
            .replace("\"unitPriceAmount\": null", "\"unitPriceAmount\": 5000")
            .replace("\"festivalStartDate\": null", "\"festivalStartDate\": \"2026-09-29\"")
            .replace("\"festivalEndDate\": null", "\"festivalEndDate\": \"2026-10-01\"")
            .replace("\"dailyTransferOpenTime\": null", "\"dailyTransferOpenTime\": \"10:00:00\"")
            .replace("\"dailyTransferCloseTime\": null", "\"dailyTransferCloseTime\": \"18:00:00\"")
            .replace("\"dailyPickupOpenTime\": null", "\"dailyPickupOpenTime\": \"11:00:00\"")
            .replace("\"dailyPickupCloseTime\": null", "\"dailyPickupCloseTime\": \"20:00:00\"");
        Path path = tempDir.resolve("ticket-catalog.json");
        Files.writeString(path, manifest);
        UUID current = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE festival_id = :festivalId AND state = 'published'",
            new MapSqlParameterSource("festivalId", FESTIVAL_ID),
            UUID.class
        );
        UUID revisionId = revisions.importManifest(
            path, "release-bot", FESTIVAL_ID, new CatalogRevisionService.BaselineOverride(current)
        );
        revisions.publish(revisionId, "release-bot");
        // A publish becomes visible after a controlled restart; reload the snapshot the same way.
        snapshots.run(null);
    }

    private OperationalAccountChange account(String accountNumber) {
        return new OperationalAccountChange(
            OperationalAccountState.CONFIGURED, "테스트은행", accountNumber, "테스트예금주", null
        );
    }

    private OperationalAccountAuditMetadata audit() {
        return new OperationalAccountAuditMetadata("release operator", "approved update", "OPS-2026-09-18");
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant = Instant.EPOCH;

        void set(OffsetDateTime time) {
            instant = time.toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.CatalogRevisionService;
import dev.espero.festival.auth.AdminPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives the public and administrator crowding endpoints through the real
 * security chain, published catalog and dynamic crowding table while the
 * clock moves across the festival calendar.
 */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class CrowdingFlowIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID INITIAL_REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    private static final UUID ADMIN_ID = UUID.fromString("5b7f9a8e-1f9c-4f0a-9b53-2f4c3a0e6d11");
    private static final Path DEVELOPMENT_CATALOG = Path.of("dev", "catalog", "development-catalog.json");
    private static final String ADMIN_ROUTE = "/api/v2/admin/crowding";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MutableClock clock;

    @TempDir
    private Path tempDir;

    private MockMvc mvc;
    private int keySequence;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clock.set(OffsetDateTime.parse("2026-09-29T12:00:00+09:00"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> resetData());
    }

    @Test
    void savesTheFirstOperatingDayBeforeTheFestivalAndKeepsPublicBeforeOpen() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        clock.set(OffsetDateTime.parse("2026-09-28T12:00:00+09:00"));

        publicCrowding()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.operatingDay").value("2026-09-29"))
            .andExpect(jsonPath("$.data.operatingStatus").value("BEFORE_OPEN"));

        assertSavedForSelectedOperatingDay("2026-09-29");
    }

    @Test
    void savesTheNextOperatingDayOnAGapDayAndKeepsPublicBeforeOpen() throws Exception {
        Path withGap = tempDir.resolve("gap-catalog.json");
        String manifest = Files.readString(DEVELOPMENT_CATALOG).replaceFirst(
            "(?s)\\{\\s*\"festivalDate\": \"2026-09-30\".*?\\},\\s*",
            ""
        );
        assertThat(manifest).doesNotContain("2026-09-30");
        Files.writeString(withGap, manifest);
        publish(withGap);
        clock.set(OffsetDateTime.parse("2026-09-30T12:00:00+09:00"));

        publicCrowding()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.operatingDay").value("2026-10-01"))
            .andExpect(jsonPath("$.data.operatingStatus").value("BEFORE_OPEN"));

        assertSavedForSelectedOperatingDay("2026-10-01");
    }

    @Test
    void savesTheLastOperatingDayAfterTheFestivalAndKeepsPublicClosed() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        clock.set(OffsetDateTime.parse("2026-10-02T12:00:00+09:00"));

        publicCrowding()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.operatingDay").value("2026-10-01"))
            .andExpect(jsonPath("$.data.operatingStatus").value("CLOSED"));

        assertSavedForSelectedOperatingDay("2026-10-01");
    }

    @Test
    void reportsAnUnconfiguredScheduleWhenThePublishedRevisionHasNoOperatingTimes() throws Exception {
        // The seeded initial revision is published but has no festival days.
        publicCrowding()
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("CROWDING_SCHEDULE_UNCONFIGURED"));
        adminCrowding()
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("CROWDING_SCHEDULE_UNCONFIGURED"));
    }

    @Test
    void reportsAnUnconfiguredScheduleWhenNoRevisionIsPublished() throws Exception {
        jdbc.update("UPDATE festival_revisions SET state = 'archived'", Map.of());

        publicCrowding()
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("CROWDING_SCHEDULE_UNCONFIGURED"));
    }

    @Test
    void savesWithTheCurrentEtagThenServesTheNewRepresentationAndRejectsStaleWrites() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        String initialEtag = adminCrowding()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("RELAXED"))
            .andExpect(jsonPath("$.data.timeBasis").value("OPENING"))
            .andReturn().getResponse().getHeader("ETag");
        assertThat(initialEtag).matches("\"[0-9a-f]{64}\"");

        mvc.perform(asAdmin(put(ADMIN_ROUTE))
                .header("Idempotency-Key", "missing-if-match")
                .contentType("application/json")
                .content("{\"level\":\"CROWDED\"}"))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));

        String key = nextKey();
        save(initialEtag, key, "CROWDED").andExpect(status().isNoContent());

        MvcResult updated = adminCrowding()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CROWDED"))
            .andExpect(jsonPath("$.data.savedLevel").value("CROWDED"))
            .andExpect(jsonPath("$.data.timeBasis").value("OPERATOR"))
            .andReturn();
        String updatedEtag = updated.getResponse().getHeader("ETag");
        assertThat(updatedEtag).isNotEqualTo(initialEtag);
        mvc.perform(get("/api/v2/crowding").header("If-None-Match", updatedEtag))
            .andExpect(status().isNotModified());
        publicCrowding().andExpect(jsonPath("$.data.status").value("CROWDED"));

        // A retry of the completed request replays its result without a second write.
        save(initialEtag, key, "CROWDED").andExpect(status().isNoContent());
        assertThat(auditCount()).isEqualTo(1);

        save(initialEtag, key, "MODERATE")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        save(initialEtag, nextKey(), "MODERATE")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EDIT_CONFLICT"));
        assertThat(savedLevel()).isEqualTo("CROWDED");
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void keepsTheSavedLevelAcrossCatalogPublishAndRollback() throws Exception {
        UUID first = publish(DEVELOPMENT_CATALOG);
        String etag = adminCrowding().andReturn().getResponse().getHeader("ETag");
        save(etag, nextKey(), "CROWDED").andExpect(status().isNoContent());

        UUID second = publish(DEVELOPMENT_CATALOG);
        assertThat(second).isNotEqualTo(first);
        publicCrowding()
            .andExpect(jsonPath("$.data.status").value("CROWDED"))
            .andExpect(jsonPath("$.data.timeBasis").value("OPERATOR"));

        revisions.rollback(first, second, "incident-bot");
        publicCrowding()
            .andExpect(jsonPath("$.data.status").value("CROWDED"))
            .andExpect(jsonPath("$.data.timeBasis").value("OPERATOR"));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM crowding_state_dynamic", Map.of(), Long.class
        )).isEqualTo(1);
    }

    private void assertSavedForSelectedOperatingDay(String operatingDay) throws Exception {
        String etag = adminCrowding()
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("ETag");

        save(etag, nextKey(), "CROWDED")
            .andExpect(status().isNoContent());

        adminCrowding()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.operatingDay").value(operatingDay))
            .andExpect(jsonPath("$.data.savedLevel").value("CROWDED"));
        assertThat(jdbc.queryForObject(
            "SELECT operating_date FROM crowding_state_dynamic", Map.of(), LocalDate.class
        )).isEqualTo(LocalDate.parse(operatingDay));
        assertThat(auditCount()).isEqualTo(1);
    }

    private ResultActions publicCrowding() throws Exception {
        return mvc.perform(get("/api/v2/crowding"));
    }

    private ResultActions adminCrowding() throws Exception {
        return mvc.perform(asAdmin(get(ADMIN_ROUTE)));
    }

    private ResultActions save(String ifMatch, String idempotencyKey, String level) throws Exception {
        return mvc.perform(asAdmin(put(ADMIN_ROUTE))
            .header("If-Match", ifMatch)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content("{\"level\":\"" + level + "\"}"));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(
            new AdminPrincipal(ADMIN_ID, "crowding-admin", "ADMIN"),
            null,
            List.of(new SimpleGrantedAuthority("ADMIN"))
        )));
    }

    private String nextKey() {
        return "crowding-flow-" + (++keySequence);
    }

    private UUID publish(Path manifest) {
        UUID current = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE festival_id = :festivalId AND state = 'published'",
            new MapSqlParameterSource("festivalId", FESTIVAL_ID),
            UUID.class
        );
        UUID revisionId = revisions.importManifest(
            manifest, "release-bot", FESTIVAL_ID, new CatalogRevisionService.BaselineOverride(current)
        );
        revisions.publish(revisionId, "release-bot");
        return revisionId;
    }

    private String savedLevel() {
        return jdbc.queryForObject("SELECT level FROM crowding_state_dynamic", Map.of(), String.class);
    }

    private long auditCount() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE admin_id = :adminId",
            new MapSqlParameterSource("adminId", ADMIN_ID),
            Long.class
        );
        return count == null ? 0 : count;
    }

    private void resetData() {
        jdbc.update("DELETE FROM crowding_state_dynamic", Map.of());
        jdbc.update("DELETE FROM admin_idempotency_records", Map.of());
        jdbc.update("DELETE FROM admin_audit_events", Map.of());
        jdbc.update("DELETE FROM admin_refresh_sessions", Map.of());
        jdbc.update("DELETE FROM admin_accounts", Map.of());
        jdbc.update("DELETE FROM catalog_revision_audit", Map.of());
        MapSqlParameterSource parameters = new MapSqlParameterSource("initialRevisionId", INITIAL_REVISION_ID);
        for (String table : new String[] {
            "prohibited_messages",
            "prohibited_item_translations",
            "prohibited_items",
            "performance_artists",
            "performance_translations",
            "performances",
            "artist_song_translations",
            "artist_songs",
            "artist_link_translations",
            "artist_links",
            "artist_translations",
            "artists",
            "timetable_configs",
            "festival_title_translations",
            "ticket_guide_translations",
            "stamp_guide_translations",
            "map_asset_translations",
            "ticket_guide_revisions",
            "stamp_guide_revisions",
            "space_map_targets",
            "map_pin_translations",
            "map_pin_filter_group_translations",
            "map_pins",
            "map_areas",
            "map_translations",
            "map_asset_versions",
            "maps",
            "place_translations",
            "places",
            "space_menu_items",
            "space_events",
            "space_sort_orders",
            "space_translations",
            "spaces",
            "festival_link_translations",
            "festival_links",
            "festival_days"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE festival_revision_id <> :initialRevisionId", parameters);
        }
        jdbc.update("DELETE FROM festival_revisions WHERE id <> :initialRevisionId", parameters);
        jdbc.update("UPDATE festival_revisions SET state = 'published' WHERE id = :initialRevisionId", parameters);
        jdbc.update("""
            INSERT INTO admin_accounts (
                id, username, password_hash, authority, enabled, created_at, updated_at, last_login_at
            ) VALUES (
                :id, 'crowding-admin', 'test-only-password-hash', 'ADMIN', true,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
            )
            """, new MapSqlParameterSource("id", ADMIN_ID));
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    /** A test clock that the flow moves across festival days. */
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

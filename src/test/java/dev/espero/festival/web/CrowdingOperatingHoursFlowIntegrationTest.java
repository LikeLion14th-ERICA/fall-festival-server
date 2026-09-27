package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.CatalogRevisionService;
import dev.espero.festival.auth.AdminPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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
@Testcontainers
class CrowdingOperatingHoursFlowIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID INITIAL_REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    private static final UUID ADMIN_ID = UUID.fromString("5b7f9a8e-1f9c-4f0a-9b53-2f4c3a0e6d11");
    private static final Path DEVELOPMENT_CATALOG = Path.of("dev", "catalog", "development-catalog.json");
    private static final String ADMIN_ROUTE = "/api/v2/admin/crowding";

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


    private static final String HOURS = "/api/v2/admin/crowding/operating-hours";
    private static final String DATE = "2026-09-29";

    @Test
    void listsPublishedHoursAndAllowsEmptySchedule() throws Exception {
        mvc.perform(asAdmin(get(HOURS))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
        publicCrowding().andExpect(status().isServiceUnavailable());
        publish(DEVELOPMENT_CATALOG);
        mvc.perform(asAdmin(get(HOURS))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].operatingDay").value(DATE))
            .andExpect(jsonPath("$.data.items[0].updatedAt").isEmpty())
            .andExpect(jsonPath("$.meta.revision").value(0));
        MvcResult result = hours(DATE).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(asAdmin(get(HOURS + "/" + DATE))
            .header("If-None-Match", result.getResponse().getHeader("ETag")))
            .andExpect(status().isNotModified());
        mvc.perform(get(HOURS)).andExpect(status().isUnauthorized());
        mvc.perform(get(HOURS).with(authentication(new UsernamePasswordAuthenticationToken(
            "viewer", null, List.of(new SimpleGrantedAuthority("USER"))))))
            .andExpect(status().isForbidden());
        hours("2026-02-30").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_DATE"));
        hours("2026-10-02").andExpect(status().isNotFound());
    }

    @Test
    void confirmsFallbackAndKeepsIdenticalSavesUnchanged() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        String initial = etag(DATE);
        String key = nextKey();
        String body = body("2026-09-29T09:00:00+09:00", "2026-09-29T23:00:00+09:00");
        saveHours(DATE, initial, key, body).andExpect(status().isNoContent());
        String confirmed = etag(DATE);
        assertThat(confirmed).isNotEqualTo(initial);
        var rows = hoursRows();
        assertThat(rows).hasSize(1);
        assertThat(auditCount()).isOne();
        saveHours(DATE, initial, key, body).andExpect(status().isNoContent());
        clock.set(OffsetDateTime.parse("2026-09-29T13:00:00+09:00"));
        saveHours(DATE, confirmed, nextKey(), body).andExpect(status().isNoContent());
        assertThat(hoursRows()).isEqualTo(rows);
        assertThat(etag(DATE)).isEqualTo(confirmed);
        assertThat(auditCount()).isOne();
        saveHours(DATE, initial, key, body("2026-09-29T00:00:00Z", "2026-09-29T14:00:00Z"))
            .andExpect(status().isNoContent()); // equivalent offsets replay
        saveHours(DATE, confirmed, key, body("2026-09-29T10:00:00+09:00", "2026-09-29T23:00:00+09:00"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void validatesInputsHeadersAndConflicts() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        String initial = etag(DATE);
        String valid = body("2026-09-29T11:00:00+09:00", "2026-09-30T00:00:00+09:00");
        mvc.perform(asAdmin(put(HOURS + "/" + DATE)).contentType("application/json").content(valid))
            .andExpect(status().isPreconditionRequired());
        mvc.perform(asAdmin(put(HOURS + "/" + DATE)).header("If-Match", initial)
            .contentType("application/json").content(valid)).andExpect(status().isPreconditionRequired());
        for (String invalid : List.of(
            body("2026-09-29T11:00:01+09:00", "2026-09-29T23:00:00+09:00"),
            body("2026-09-29T11:00:00.000+09:00", "2026-09-29T23:00:00+09:00"),
            body("2026-09-28T23:00:00+09:00", "2026-09-29T23:00:00+09:00"),
            body("2026-09-29T11:00:00+09:00", "2026-09-30T01:01:00+09:00"),
            body("2026-09-29T11:00:00+09:00", "2026-09-29T11:00:00+09:00"),
            "{\"opensAt\":null,\"closesAt\":null}",
            valid.substring(0, valid.length()-1) + ",\"unexpected\":true}")) {
            saveHours(DATE, initial, nextKey(), invalid).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        saveHours("2026-10-02", initial, nextKey(), body("2026-10-02T11:00:00+09:00", "2026-10-03T00:00:00+09:00"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_FESTIVAL_DAY"));
        saveHours(DATE, initial, nextKey(), valid).andExpect(status().isNoContent());
        saveHours(DATE, initial, nextKey(), valid).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EDIT_CONFLICT"));
        assertThat(auditCount()).isOne();
    }

    @Test
    void shorteningAndExtendingRestoresLevelAndHonorsBoundaries() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        String levelTag = adminCrowding().andReturn().getResponse().getHeader("ETag");
        save(levelTag, nextKey(), "CROWDED").andExpect(status().isNoContent());
        var saved = crowdingStateRows();
        saveHours(DATE, etag(DATE), nextKey(), body("2026-09-29T11:00:00+09:00", "2026-09-29T12:00:00+09:00"))
            .andExpect(status().isNoContent());
        publicCrowding().andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.updatedAt").isEmpty());
        saveHours(DATE, etag(DATE), nextKey(), body("2026-09-29T11:00:00+09:00", "2026-09-30T00:00:00+09:00"))
            .andExpect(status().isNoContent());
        publicCrowding().andExpect(jsonPath("$.data.status").value("CROWDED"));
        assertThat(crowdingStateRows()).isEqualTo(saved);
        clock.set(OffsetDateTime.parse("2026-09-29T10:59:59+09:00"));
        publicCrowding().andExpect(jsonPath("$.data.status").value("BEFORE_OPEN"));
        clock.set(OffsetDateTime.parse("2026-09-29T11:00:00+09:00"));
        publicCrowding().andExpect(jsonPath("$.data.status").value("CROWDED"));
        clock.set(OffsetDateTime.parse("2026-09-29T23:59:59+09:00"));
        publicCrowding().andExpect(jsonPath("$.data.status").value("CROWDED"));
        clock.set(OffsetDateTime.parse("2026-09-30T00:00:00+09:00"));
        publicCrowding().andExpect(jsonPath("$.data.operatingDay").value("2026-09-30"))
            .andExpect(jsonPath("$.data.status").value("BEFORE_OPEN"));
    }

    @Test
    void retainsOverridesThroughRepublishRemovalReadditionAndRollback() throws Exception {
        UUID original = publish(DEVELOPMENT_CATALOG);
        String retainedDay = "2026-09-30";
        String key = nextKey();
        String tag = etag(retainedDay);
        String value = body("2026-09-30T14:00:00+09:00", "2026-10-01T00:00:00+09:00");
        saveHours(retainedDay, tag, key, value).andExpect(status().isNoContent());
        var rows = hoursRows();
        Path without = tempDir.resolve("without-day.json");
        String manifest = Files.readString(DEVELOPMENT_CATALOG)
            .replaceFirst("(?s)\\{\\s*\"festivalDate\": \"2026-09-30\".*?\\},\\s*", "");
        Files.writeString(without, manifest);
        publish(without);
        hours(retainedDay).andExpect(status().isNotFound());
        assertThat(hoursRows()).isEqualTo(rows);
        saveHours(retainedDay, tag, key, value).andExpect(status().isNoContent());
        saveHours(retainedDay, tag, nextKey(), value).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_FESTIVAL_DAY"));
        UUID beforeRollback = publish(DEVELOPMENT_CATALOG);
        hours(retainedDay).andExpect(jsonPath("$.data.opensAt").value("2026-09-30T14:00:00+09:00"));
        revisions.rollback(original, beforeRollback, "release-bot");
        assertThat(hoursRows()).isEqualTo(rows);
        hours(retainedDay).andExpect(jsonPath("$.data.opensAt").value("2026-09-30T14:00:00+09:00"));
        var json = new tools.jackson.databind.ObjectMapper();
        var document = json.readTree(Files.readString(DEVELOPMENT_CATALOG));
        ((tools.jackson.databind.node.ArrayNode) document.get("festivalDays")).addObject()
            .put("festivalDate", "2026-10-02").put("opensAt", "2026-10-02T09:00:00+09:00")
            .put("closesAt", "2026-10-02T23:00:00+09:00");
        Path withNewDay = tempDir.resolve("new-day.json");
        Files.writeString(withNewDay, json.writeValueAsString(document));
        publish(withNewDay);
        hours("2026-10-02").andExpect(jsonPath("$.data.updatedAt").isEmpty())
            .andExpect(jsonPath("$.data.opensAt").value("2026-10-02T09:00:00+09:00"));
        assertThat(hoursRows()).isEqualTo(rows);
    }

    @Test
    void repairsInvalidFallbackWithoutBlockingAdministratorReads() throws Exception {
        UUID revision = publish(DEVELOPMENT_CATALOG);
        jdbc.update("UPDATE festival_days SET opens_at = opens_at + INTERVAL '31 seconds', "
            + "closes_at = ((festival_date + 1)::timestamp AT TIME ZONE 'Asia/Seoul') + INTERVAL '61 minutes' "
            + "WHERE festival_revision_id = :id",
            Map.of("id", revision));
        mvc.perform(asAdmin(get(HOURS))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].opensAt").value("2026-09-29T09:00:31+09:00"));
        saveHours(DATE, etag(DATE), nextKey(), body("2026-09-29T11:00:00+09:00", "2026-09-30T00:00:00+09:00"))
            .andExpect(status().isNoContent());
        publicCrowding().andExpect(status().isServiceUnavailable()); // another invalid day remains
        for (String day : List.of("2026-09-30", "2026-10-01")) {
            saveHours(day, etag(day), nextKey(), body(day + "T11:00:00+09:00", day + "T23:00:00+09:00"))
                .andExpect(status().isNoContent());
        }
        publicCrowding().andExpect(status().isOk());
    }

    @Test
    void preservesAndEditsPreviousOperatingDayDuringExtendedNight() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        saveHours(DATE, etag(DATE), nextKey(), body("2026-09-29T11:00:00+09:00", "2026-09-30T01:00:00+09:00"))
            .andExpect(status().isNoContent());
        save(adminCrowding().andReturn().getResponse().getHeader("ETag"), nextKey(), "CROWDED")
            .andExpect(status().isNoContent());
        var original = crowdingStateRows();
        clock.set(OffsetDateTime.parse("2026-09-30T00:30:00+09:00"));
        publicCrowding().andExpect(jsonPath("$.data.operatingDay").value(DATE))
            .andExpect(jsonPath("$.data.status").value("CROWDED"));
        assertThat(crowdingStateRows()).isEqualTo(original);
        save(adminCrowding().andReturn().getResponse().getHeader("ETag"), nextKey(), "MODERATE")
            .andExpect(status().isNoContent());
        var nightState = crowdingStateRows();
        assertThat(nightState).hasSize(1);
        assertThat(nightState.getFirst().get("operating_date").toString()).isEqualTo(DATE);
        saveHours(DATE, etag(DATE), nextKey(), body("2026-09-29T11:00:00+09:00", "2026-09-30T00:20:00+09:00"))
            .andExpect(status().isNoContent());
        publicCrowding().andExpect(jsonPath("$.data.operatingDay").value("2026-09-30"))
            .andExpect(jsonPath("$.data.status").value("BEFORE_OPEN"));
        saveHours(DATE, etag(DATE), nextKey(), body("2026-09-29T11:00:00+09:00", "2026-09-30T01:00:00+09:00"))
            .andExpect(status().isNoContent());
        publicCrowding().andExpect(jsonPath("$.data.operatingDay").value(DATE))
            .andExpect(jsonPath("$.data.status").value("MODERATE"));
        assertThat(crowdingStateRows()).isEqualTo(nightState);
        clock.set(OffsetDateTime.parse("2026-09-30T01:00:00+09:00"));
        publicCrowding().andExpect(jsonPath("$.data.operatingDay").value("2026-09-30"))
            .andExpect(jsonPath("$.data.status").value("BEFORE_OPEN"))
            .andExpect(jsonPath("$.data.savedLevel").isEmpty());
    }

    @Test
    void rollsBackHoursAuditAndIdempotencyCompletionTogether() throws Exception {
        publish(DEVELOPMENT_CATALOG);
        String tag = etag(DATE);
        String key = nextKey();
        String value = body("2026-09-29T11:00:00+09:00", "2026-09-30T00:00:00+09:00");
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION reject_hours_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit failure'; END $$");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER reject_hours_audit BEFORE INSERT ON admin_audit_events FOR EACH ROW EXECUTE FUNCTION reject_hours_audit()");
        try {
            saveHours(DATE, tag, key, value).andExpect(status().is5xxServerError());
            assertThat(hoursRows()).isEmpty();
            assertThat(auditCount()).isZero();
            assertThat(etag(DATE)).isEqualTo(tag);
        } finally {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER reject_hours_audit ON admin_audit_events");
            jdbc.getJdbcTemplate().execute("DROP FUNCTION reject_hours_audit()");
        }
        clock.set(OffsetDateTime.parse("2026-09-29T12:03:00+09:00")); // existing reservation lease expires
        saveHours(DATE, tag, key, value).andExpect(status().isNoContent());
        assertThat(auditCount()).isOne();
    }

    private ResultActions hours(String date) throws Exception {
        return mvc.perform(asAdmin(get(HOURS + "/" + date)));
    }

    private String etag(String date) throws Exception {
        return hours(date).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private ResultActions saveHours(String date, String tag, String key, String body) throws Exception {
        return mvc.perform(asAdmin(put(HOURS + "/" + date)).header("If-Match", tag)
            .header("Idempotency-Key", key).contentType("application/json").content(body));
    }

    private String body(String opensAt, String closesAt) {
        return "{\"opensAt\":\"" + opensAt + "\",\"closesAt\":\"" + closesAt + "\"}";
    }

    private List<Map<String, Object>> hoursRows() {
        return jdbc.queryForList("SELECT * FROM crowding_operating_hours ORDER BY operating_date", Map.of());
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

    private List<Map<String, Object>> crowdingStateRows() {
        return jdbc.queryForList(
            """
            SELECT festival_id::text AS festival_id, operating_date, level, updated_at
            FROM crowding_state_dynamic
            ORDER BY festival_id, operating_date
            """,
            Map.of()
        );
    }

    private List<Map<String, Object>> auditRows() {
        return jdbc.queryForList(
            """
            SELECT id::text AS id, admin_id::text AS admin_id, action, resource_type,
                   resource_id, occurred_at, request_id
            FROM admin_audit_events
            WHERE admin_id = :adminId
            ORDER BY occurred_at, id
            """,
            new MapSqlParameterSource("adminId", ADMIN_ID)
        );
    }

    private void resetData() {
        jdbc.update("DELETE FROM crowding_operating_hours", Map.of());
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
            "stamp_booth_tokens",
            "stamp_booths",
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

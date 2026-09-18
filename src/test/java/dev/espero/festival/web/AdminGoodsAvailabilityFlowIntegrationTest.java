package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.auth.AdminPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the administrator goods availability mutation against PostgreSQL. */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminGoodsAvailabilityFlowIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID OTHER_FESTIVAL_ID = UUID.fromString("4b028799-51c2-43fb-943c-f55ec5c60d99");
    private static final UUID ADMIN_ID = UUID.fromString("a4d71f22-4d36-42eb-9bde-1829936215c2");

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
    private MutableClock clock;

    private MockMvc mvc;
    private int keySequence;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clock.set("2030-10-01T12:00:00+09:00");
        jdbc.update("DELETE FROM goods_combinations", Map.of());
        jdbc.update("DELETE FROM goods_color_translations", Map.of());
        jdbc.update("DELETE FROM goods_colors", Map.of());
        jdbc.update("DELETE FROM goods_size_translations", Map.of());
        jdbc.update("DELETE FROM goods_sizes", Map.of());
        jdbc.update("DELETE FROM goods_translations", Map.of());
        jdbc.update("DELETE FROM goods", Map.of());
        jdbc.update("DELETE FROM admin_idempotency_records", Map.of());
        jdbc.update("DELETE FROM admin_audit_events", Map.of());
        jdbc.update("DELETE FROM admin_refresh_sessions", Map.of());
        jdbc.update("DELETE FROM admin_accounts", Map.of());
        jdbc.update("DELETE FROM festivals WHERE id = :id", Map.of("id", OTHER_FESTIVAL_ID));
        jdbc.update("""
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES (:id, 'Other festival', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", OTHER_FESTIVAL_ID));
        jdbc.update("""
            INSERT INTO admin_accounts (
                id, username, password_hash, authority, enabled, created_at, updated_at, last_login_at
            ) VALUES (
                :id, 'goods-admin', 'test-only-password-hash', 'ADMIN', true,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
            )
            """, Map.of("id", ADMIN_ID));
    }

    @Test
    void updatesOnlyTheOwnedCombinationWithoutIfMatchAndReturnsTheCurrentAvailability() throws Exception {
        GoodsFixture goods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE", "ON_SALE");
        OffsetDateTime goodsUpdatedAt = goodsTimestamp(goods.goodsId());
        OffsetDateTime otherCombinationUpdatedAt = combinationTimestamp(goods.combinationIds().get(1));

        putAvailability(goods.goodsId(), goods.combinationIds().getFirst(), "SOLD_OUT", nextKey())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.goodsId").value(goods.goodsId().toString()))
            .andExpect(jsonPath(
                "$.data.combinations[?(@.combinationId == '" + goods.combinationIds().getFirst() + "')].status"
            ).value(org.hamcrest.Matchers.contains("SOLD_OUT")))
            .andExpect(jsonPath(
                "$.data.combinations[?(@.combinationId == '" + goods.combinationIds().get(1) + "')].status"
            ).value(org.hamcrest.Matchers.contains("ON_SALE")))
            .andExpect(jsonPath("$.data.allSoldOut").value(false))
            .andExpect(jsonPath("$.data.updatedAt").value("2030-10-01T12:00:00+09:00"))
            .andExpect(jsonPath("$.meta.revision").value(0));

        assertThat(combinationStatus(goods.combinationIds().getFirst())).isEqualTo("SOLD_OUT");
        assertThat(combinationTimestamp(goods.combinationIds().getFirst()).toInstant()).isEqualTo(clock.instant());
        assertThat(combinationTimestamp(goods.combinationIds().get(1))).isEqualTo(otherCombinationUpdatedAt);
        assertThat(goodsTimestamp(goods.goodsId())).isEqualTo(goodsUpdatedAt);
        assertThat(auditCount()).isEqualTo(1);
        Map<String, Object> audit = auditEvent();
        assertThat(audit.get("action")).isEqualTo("GOODS_AVAILABILITY_UPDATED");
        assertThat(audit.get("resource_type")).isEqualTo("GOODS");
        assertThat(audit.get("resource_id")).isEqualTo(
            goods.goodsId() + "/" + goods.combinationIds().getFirst()
        );
    }

    @Test
    void derivesAllSoldOutAndCanReverseTheLastWrite() throws Exception {
        GoodsFixture goods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE", "SOLD_OUT");
        UUID target = goods.combinationIds().getFirst();

        putAvailability(goods.goodsId(), target, "SOLD_OUT", nextKey())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allSoldOut").value(true));

        clock.set("2030-10-01T12:05:00+09:00");
        putAvailability(goods.goodsId(), target, "ON_SALE", nextKey())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allSoldOut").value(false))
            .andExpect(jsonPath(
                "$.data.combinations[?(@.combinationId == '" + target + "')].status"
            ).value(org.hamcrest.Matchers.contains("ON_SALE")));
    }

    @Test
    void rejectsInvalidMissingLegacyAndExtraInput() throws Exception {
        GoodsFixture goods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE");
        UUID combinationId = goods.combinationIds().getFirst();

        putJson(goods.goodsId(), combinationId, "{\"status\":\"UNKNOWN\"}", nextKey())
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putJson(goods.goodsId(), combinationId, "{}", nextKey())
            .andExpect(status().isUnprocessableEntity());
        putJson(goods.goodsId(), combinationId, "{\"quantity\":3}", nextKey())
            .andExpect(status().isUnprocessableEntity());
        putJson(goods.goodsId(), combinationId, "{\"status\":\"ON_SALE\",\"quantity\":3}", nextKey())
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(asAdmin(put(route(goods.goodsId(), combinationId)))
                .queryParam("unexpected", "x")
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content("{\"status\":\"ON_SALE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
    }

    @Test
    void requiresOneValidIdempotencyKey() throws Exception {
        GoodsFixture goods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE");
        String route = route(goods.goodsId(), goods.combinationIds().getFirst());

        mvc.perform(asAdmin(put(route))
                .contentType("application/json")
                .content("{\"status\":\"SOLD_OUT\"}"))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(asAdmin(put(route))
                .header("Idempotency-Key", "invalid key")
                .contentType("application/json")
                .content("{\"status\":\"SOLD_OUT\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void replaysTheSameRequestWithoutRepeatingTheMutationAndRejectsKeyReuse() throws Exception {
        GoodsFixture goods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE");
        UUID combinationId = goods.combinationIds().getFirst();
        String key = nextKey();

        MvcResult first = putAvailability(goods.goodsId(), combinationId, "SOLD_OUT", key)
            .andExpect(status().isOk())
            .andReturn();
        OffsetDateTime firstUpdatedAt = combinationTimestamp(combinationId);

        clock.set("2030-10-01T13:00:00+09:00");
        MvcResult replay = putAvailability(goods.goodsId(), combinationId, "SOLD_OUT", key)
            .andExpect(status().isOk())
            .andReturn();

        assertThat(replay.getResponse().getContentAsString())
            .isEqualTo(first.getResponse().getContentAsString());
        assertThat(combinationTimestamp(combinationId)).isEqualTo(firstUpdatedAt);
        assertThat(auditCount()).isEqualTo(1);

        putAvailability(goods.goodsId(), combinationId, "ON_SALE", key)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(combinationStatus(combinationId)).isEqualTo("SOLD_OUT");
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void hidesUnknownAndCrossOwnershipCombinationsBehindNotFound() throws Exception {
        GoodsFixture owned = insertOptionsGoods(FESTIVAL_ID, "ON_SALE");
        GoodsFixture anotherGoods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE");
        GoodsFixture anotherFestival = insertOptionsGoods(OTHER_FESTIVAL_ID, "ON_SALE");

        putAvailability(UUID.randomUUID(), UUID.randomUUID(), "SOLD_OUT", nextKey())
            .andExpect(status().isNotFound());
        putAvailability(owned.goodsId(), UUID.randomUUID(), "SOLD_OUT", nextKey())
            .andExpect(status().isNotFound());
        putAvailability(owned.goodsId(), anotherGoods.combinationIds().getFirst(), "SOLD_OUT", nextKey())
            .andExpect(status().isNotFound());
        putAvailability(anotherFestival.goodsId(), anotherFestival.combinationIds().getFirst(), "SOLD_OUT", nextKey())
            .andExpect(status().isNotFound());

        assertThat(combinationStatus(owned.combinationIds().getFirst())).isEqualTo("ON_SALE");
        assertThat(combinationStatus(anotherGoods.combinationIds().getFirst())).isEqualTo("ON_SALE");
        assertThat(combinationStatus(anotherFestival.combinationIds().getFirst())).isEqualTo("ON_SALE");
        assertThat(auditCount()).isZero();
    }

    @Test
    void aNewKeyForTheSameStatusPerformsAnOrdinaryPutAndRecordsAudit() throws Exception {
        GoodsFixture goods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE");
        UUID combinationId = goods.combinationIds().getFirst();

        putAvailability(goods.goodsId(), combinationId, "ON_SALE", nextKey())
            .andExpect(status().isOk());
        OffsetDateTime firstUpdatedAt = combinationTimestamp(combinationId);

        clock.set("2030-10-01T12:10:00+09:00");
        putAvailability(goods.goodsId(), combinationId, "ON_SALE", nextKey())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.updatedAt").value("2030-10-01T12:10:00+09:00"));

        assertThat(combinationTimestamp(combinationId).toInstant()).isEqualTo(clock.instant());
        assertThat(combinationTimestamp(combinationId)).isAfter(firstUpdatedAt);
        assertThat(auditCount()).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions putAvailability(
        UUID goodsId,
        UUID combinationId,
        String availability,
        String key
    ) throws Exception {
        return putJson(goodsId, combinationId, "{\"status\":\"" + availability + "\"}", key);
    }

    private org.springframework.test.web.servlet.ResultActions putJson(
        UUID goodsId,
        UUID combinationId,
        String body,
        String key
    ) throws Exception {
        return mvc.perform(asAdmin(put(route(goodsId, combinationId)))
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(
            new AdminPrincipal(ADMIN_ID, "goods-admin", "ADMIN"),
            null,
            List.of(new SimpleGrantedAuthority("ADMIN"))
        )));
    }

    private String route(UUID goodsId, UUID combinationId) {
        return "/api/v2/admin/goods/" + goodsId + "/combinations/" + combinationId + "/availability";
    }

    private String nextKey() {
        return "goods-availability-" + (++keySequence);
    }

    private GoodsFixture insertOptionsGoods(UUID festivalId, String... statuses) {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'OPTIONS', 15000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", festivalId));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'ko', '테스트 상품')",
            Map.of("id", goodsId));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'en', 'Test Goods')",
            Map.of("id", goodsId));
        UUID sizeId = UUID.randomUUID();
        jdbc.update("INSERT INTO goods_sizes (id, goods_id, sort_order) VALUES (:id, :goodsId, 0)",
            Map.of("id", sizeId, "goodsId", goodsId));
        jdbc.update("INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'ko', 'M')",
            Map.of("id", sizeId));
        jdbc.update("INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'en', 'M')",
            Map.of("id", sizeId));

        List<UUID> combinationIds = new ArrayList<>();
        for (int index = 0; index < statuses.length; index++) {
            UUID colorId = UUID.randomUUID();
            jdbc.update("INSERT INTO goods_colors (id, goods_id, sort_order) VALUES (:id, :goodsId, :sortOrder)",
                new MapSqlParameterSource().addValue("id", colorId).addValue("goodsId", goodsId)
                    .addValue("sortOrder", index));
            jdbc.update("INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'ko', :name)",
                Map.of("id", colorId, "name", "색상 " + index));
            jdbc.update("INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'en', :name)",
                Map.of("id", colorId, "name", "Color " + index));
            UUID combinationId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO goods_combinations (id, goods_id, color_id, size_id, availability, updated_at)
                VALUES (:id, :goodsId, :colorId, :sizeId, :availability, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("id", combinationId)
                .addValue("goodsId", goodsId)
                .addValue("colorId", colorId)
                .addValue("sizeId", sizeId)
                .addValue("availability", statuses[index]));
            combinationIds.add(combinationId);
        }
        return new GoodsFixture(goodsId, combinationIds);
    }

    private String combinationStatus(UUID combinationId) {
        return jdbc.queryForObject(
            "SELECT availability FROM goods_combinations WHERE id = :id",
            Map.of("id", combinationId),
            String.class
        );
    }

    private OffsetDateTime combinationTimestamp(UUID combinationId) {
        return jdbc.queryForObject(
            "SELECT updated_at FROM goods_combinations WHERE id = :id",
            Map.of("id", combinationId),
            OffsetDateTime.class
        );
    }

    private OffsetDateTime goodsTimestamp(UUID id) {
        return jdbc.queryForObject(
            "SELECT updated_at FROM goods WHERE id = :id",
            Map.of("id", id),
            OffsetDateTime.class
        );
    }

    private long auditCount() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE admin_id = :adminId",
            Map.of("adminId", ADMIN_ID),
            Long.class
        );
        return count == null ? 0 : count;
    }

    private Map<String, Object> auditEvent() {
        return jdbc.queryForMap(
            "SELECT action, resource_type, resource_id FROM admin_audit_events WHERE admin_id = :adminId",
            Map.of("adminId", ADMIN_ID)
        );
    }

    private record GoodsFixture(UUID goodsId, List<UUID> combinationIds) {}

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

        void set(String value) {
            instant = OffsetDateTime.parse(value).toInstant();
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

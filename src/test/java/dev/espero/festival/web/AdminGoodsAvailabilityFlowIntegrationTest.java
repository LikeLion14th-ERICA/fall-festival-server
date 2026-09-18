package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    @Autowired
    private ObjectMapper objectMapper;

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
    void listsMixedAndSoldOutGoodsForAnAuthenticatedAdminWithUnscopedKoreanMeta() throws Exception {
        GoodsFixture mixed = insertOptionsGoods(FESTIVAL_ID, "ON_SALE", "SOLD_OUT");
        GoodsFixture soldOut = insertOptionsGoods(FESTIVAL_ID, "SOLD_OUT", "SOLD_OUT");

        MvcResult result = mvc.perform(asAdmin(get("/api/v2/admin/goods")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.locale").value("ko"))
            .andReturn();

        JsonNode mixedItem = item(result, mixed.goodsId());
        assertThat(statuses(mixedItem)).containsExactlyInAnyOrder("ON_SALE", "SOLD_OUT");
        assertThat(mixedItem.path("allSoldOut").asBoolean()).isFalse();
        assertThat(mixedItem.path("updatedAt").asString()).isNotBlank();

        JsonNode soldOutItem = item(result, soldOut.goodsId());
        assertThat(statuses(soldOutItem)).containsOnly("SOLD_OUT");
        assertThat(soldOutItem.path("allSoldOut").asBoolean()).isTrue();
        assertThat(auditCount()).isZero();
        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void listsASingleGoodsWithItsOpaqueCombination() throws Exception {
        GoodsFixture single = insertSingleGoods(FESTIVAL_ID, "ON_SALE");

        MvcResult result = mvc.perform(asAdmin(get("/api/v2/admin/goods")))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode item = item(result, single.goodsId());
        JsonNode combination = item.path("combinations").get(0);
        assertThat(combination.path("combinationId").asString())
            .isEqualTo(single.combinationIds().getFirst().toString());
        assertThat(combination.has("colorId")).isTrue();
        assertThat(combination.path("colorId").isNull()).isTrue();
        assertThat(combination.has("sizeId")).isTrue();
        assertThat(combination.path("sizeId").isNull()).isTrue();
        assertThat(combination.path("status").asString()).isEqualTo("ON_SALE");
        assertThat(item.path("allSoldOut").asBoolean()).isFalse();
    }

    @Test
    void returnsAnEmptyListWhenNoGoodsExist() throws Exception {
        mvc.perform(asAdmin(get("/api/v2/admin/goods")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.empty()))
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.locale").value("ko"));
    }

    @Test
    void rejectsQueriesAndRequiresAdministratorAuthentication() throws Exception {
        mvc.perform(asAdmin(get("/api/v2/admin/goods")).queryParam("locale", "ko"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));

        mvc.perform(get("/api/v2/admin/goods"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void listsAdminProductsWithRawTranslationsOptionsAndKoreanMeta() throws Exception {
        GoodsFixture goods = insertAdminOptionsGoods(
            FESTIVAL_ID,
            OffsetDateTime.parse("2030-09-30T10:00:00+09:00"),
            OffsetDateTime.parse("2030-10-01T11:00:00+09:00")
        );

        MvcResult result = mvc.perform(asAdmin(get("/api/v2/admin/products")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.locale").value("ko"))
            .andReturn();

        JsonNode item = adminProductItem(result, goods.goodsId());
        assertThat(item.path("optionMode").asString()).isEqualTo("OPTIONS");
        assertThat(item.path("translations").path("ko").path("name").asString()).isEqualTo("관리자 상품");
        assertThat(item.path("translations").path("en").path("description").asString())
            .isEqualTo("Admin description");
        assertThat(item.path("translations").path("zh-Hans").path("name").asString()).isEqualTo("管理员商品");
        assertThat(item.path("translations").has("ja")).isTrue();
        assertThat(item.path("translations").path("ja").isNull()).isTrue();
        assertThat(item.path("price").path("amount").asLong()).isEqualTo(15000);
        assertThat(item.path("price").path("currency").asString()).isEqualTo("KRW");
        assertThat(item.path("colors")).hasSize(2);
        assertThat(item.path("colors").get(0).path("translations").path("ko").path("name").asString())
            .isEqualTo("검정");
        assertThat(item.path("colors").get(0).path("translations").path("zh-Hans").isNull()).isTrue();
        assertThat(item.path("sizes")).hasSize(2);
        assertThat(item.path("sizes").get(0).path("translations").path("en").path("label").asString())
            .isEqualTo("M");
        assertThat(item.path("sizes").get(0).path("translations").path("ja").isNull()).isTrue();
        assertThat(item.path("combinations")).hasSize(2);
        assertThat(statuses(item)).containsExactlyInAnyOrder("ON_SALE", "SOLD_OUT");
        assertThat(item.path("createdAt").asString()).isEqualTo("2030-09-30T10:00:00+09:00");
        assertThat(item.path("updatedAt").asString()).isEqualTo("2030-10-01T11:00:00+09:00");
        assertThat(auditCount()).isZero();
        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void listsSingleAdminProductWithOneOpaqueCombination() throws Exception {
        GoodsFixture single = insertSingleGoods(FESTIVAL_ID, "ON_SALE");

        MvcResult result = mvc.perform(asAdmin(get("/api/v2/admin/products")))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode item = adminProductItem(result, single.goodsId());
        assertThat(item.path("optionMode").asString()).isEqualTo("SINGLE");
        assertThat(item.path("colors")).isEmpty();
        assertThat(item.path("sizes")).isEmpty();
        assertThat(item.path("translations").path("zh-Hans").isNull()).isTrue();
        assertThat(item.path("translations").path("ja").isNull()).isTrue();
        assertThat(item.path("combinations")).hasSize(1);
        JsonNode combination = item.path("combinations").get(0);
        assertThat(combination.path("combinationId").asString())
            .isEqualTo(single.combinationIds().getFirst().toString());
        assertThat(combination.path("colorId").isNull()).isTrue();
        assertThat(combination.path("sizeId").isNull()).isTrue();
        assertThat(combination.path("status").asString()).isEqualTo("ON_SALE");
    }

    @Test
    void ordersAdminProductsByUpdatedAtDescendingThenIdAscending() throws Exception {
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID newestId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        OffsetDateTime older = OffsetDateTime.parse("2030-10-01T10:00:00+09:00");
        OffsetDateTime newest = OffsetDateTime.parse("2030-10-01T11:00:00+09:00");
        insertTimedSingleGoods(FESTIVAL_ID, secondId, older);
        insertTimedSingleGoods(FESTIVAL_ID, firstId, older);
        insertTimedSingleGoods(FESTIVAL_ID, newestId, newest);

        MvcResult result = mvc.perform(asAdmin(get("/api/v2/admin/products")))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("items");
        assertThat(items).extracting(node -> node.path("id").asString()).containsExactly(
            newestId.toString(),
            firstId.toString(),
            secondId.toString()
        );
    }

    @Test
    void returnsAnEmptyAdminProductList() throws Exception {
        mvc.perform(asAdmin(get("/api/v2/admin/products")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.empty()))
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.locale").value("ko"));
    }

    @Test
    void rejectsAdminProductQueriesAndRequiresAdministratorAuthentication() throws Exception {
        mvc.perform(asAdmin(get("/api/v2/admin/products")).queryParam("locale", "ko"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(asAdmin(get("/api/v2/admin/products")).queryParam("foo", "bar"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(asAdmin(get("/api/v2/admin/products")).queryParam("page", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));

        mvc.perform(get("/api/v2/admin/products"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void getsAdminProductDetailWithStableStrongEtagAndConditionalRevalidation() throws Exception {
        GoodsFixture goods = insertAdminOptionsGoods(
            FESTIVAL_ID,
            OffsetDateTime.parse("2030-09-30T10:00:00+09:00"),
            OffsetDateTime.parse("2030-10-01T11:00:00+09:00")
        );

        MvcResult first = mvc.perform(asAdmin(get(adminProductRoute(goods.goodsId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(goods.goodsId().toString()))
            .andExpect(jsonPath("$.data.optionMode").value("OPTIONS"))
            .andExpect(jsonPath("$.data.translations.ko.name").value("관리자 상품"))
            .andExpect(jsonPath("$.data.translations.en.name").value("Admin Goods"))
            .andExpect(jsonPath("$.data.translations.zh-Hans.name").value("管理员商品"))
            .andExpect(jsonPath("$.data.translations.ja").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.price.amount").value(15000))
            .andExpect(jsonPath("$.data.price.currency").value("KRW"))
            .andExpect(jsonPath("$.data.colors", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.data.sizes", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.data.combinations", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.data.createdAt").value("2030-09-30T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.updatedAt").value("2030-10-01T11:00:00+09:00"))
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.locale").value("ko"))
            .andExpect(jsonPath("$.meta.requestId").doesNotExist())
            .andExpect(jsonPath("$.meta.serverTime").doesNotExist())
            .andReturn();

        String firstEtag = first.getResponse().getHeader("ETag");
        String firstRequestId = first.getResponse().getHeader("X-Request-Id");
        String firstServerTime = first.getResponse().getHeader("X-Server-Time");
        assertThat(firstEtag).matches("\"[0-9a-f]{64}\"");
        assertThat(firstRequestId).isNotBlank();
        assertThat(OffsetDateTime.parse(firstServerTime))
            .isEqualTo(OffsetDateTime.parse("2030-10-01T12:00:00+09:00"));

        clock.set("2030-10-01T12:01:00+09:00");
        MvcResult second = mvc.perform(asAdmin(get(adminProductRoute(goods.goodsId()))))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(second.getResponse().getHeader("ETag")).isEqualTo(firstEtag);
        assertThat(second.getResponse().getHeader("X-Request-Id")).isNotEqualTo(firstRequestId);
        assertThat(second.getResponse().getHeader("X-Server-Time")).isNotEqualTo(firstServerTime);

        mvc.perform(asAdmin(get(adminProductRoute(goods.goodsId()))).header("If-None-Match", firstEtag))
            .andExpect(status().isNotModified())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    @Test
    void changesAdminProductDetailEtagAfterAvailabilityMutation() throws Exception {
        GoodsFixture goods = insertOptionsGoods(FESTIVAL_ID, "ON_SALE", "SOLD_OUT");
        UUID combinationId = goods.combinationIds().getFirst();
        String before = mvc.perform(asAdmin(get(adminProductRoute(goods.goodsId()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");

        putAvailability(goods.goodsId(), combinationId, "SOLD_OUT", nextKey())
            .andExpect(status().isOk());

        MvcResult afterResult = mvc.perform(asAdmin(get(adminProductRoute(goods.goodsId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.data.combinations[?(@.combinationId == '" + combinationId + "')].status"
            ).value(org.hamcrest.Matchers.contains("SOLD_OUT")))
            .andReturn();
        assertThat(afterResult.getResponse().getHeader("ETag")).isNotEqualTo(before);
    }

    @Test
    void getsSingleAdminProductDetailWithOneOpaqueCombination() throws Exception {
        GoodsFixture single = insertSingleGoods(FESTIVAL_ID, "ON_SALE");

        mvc.perform(asAdmin(get(adminProductRoute(single.goodsId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.optionMode").value("SINGLE"))
            .andExpect(jsonPath("$.data.colors", org.hamcrest.Matchers.empty()))
            .andExpect(jsonPath("$.data.sizes", org.hamcrest.Matchers.empty()))
            .andExpect(jsonPath("$.data.combinations", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.combinations[0].combinationId")
                .value(single.combinationIds().getFirst().toString()))
            .andExpect(jsonPath("$.data.combinations[0].colorId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.combinations[0].sizeId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void hidesMissingAndCrossFestivalAdminProductDetailsBehindNotFound() throws Exception {
        GoodsFixture otherFestival = insertSingleGoods(OTHER_FESTIVAL_ID, "ON_SALE");

        mvc.perform(asAdmin(get(adminProductRoute(UUID.randomUUID()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(asAdmin(get(adminProductRoute(otherFestival.goodsId()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsAdminProductDetailQueriesAndRequiresAdministratorAuthentication() throws Exception {
        GoodsFixture goods = insertSingleGoods(FESTIVAL_ID, "ON_SALE");

        mvc.perform(asAdmin(get(adminProductRoute(goods.goodsId()))).queryParam("locale", "ko"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(asAdmin(get(adminProductRoute(goods.goodsId()))).queryParam("foo", "bar"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));

        mvc.perform(get(adminProductRoute(goods.goodsId())))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
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

    private String adminProductRoute(UUID goodsId) {
        return "/api/v2/admin/products/" + goodsId;
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

    private GoodsFixture insertAdminOptionsGoods(
        UUID festivalId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'OPTIONS', 15000, :createdAt, :updatedAt)
            """, new MapSqlParameterSource()
            .addValue("id", goodsId)
            .addValue("festivalId", festivalId)
            .addValue("createdAt", createdAt)
            .addValue("updatedAt", updatedAt));
        insertGoodsTranslation(goodsId, "ko", "관리자 상품", "관리자 설명");
        insertGoodsTranslation(goodsId, "en", "Admin Goods", "Admin description");
        insertGoodsTranslation(goodsId, "zh-Hans", "管理员商品", "管理员说明");

        List<UUID> colorIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<String> colorNames = List.of("검정", "흰색");
        List<String> colorNamesEn = List.of("Black", "White");
        for (int index = 0; index < colorIds.size(); index++) {
            UUID colorId = colorIds.get(index);
            jdbc.update("INSERT INTO goods_colors (id, goods_id, sort_order) VALUES (:id, :goodsId, :sortOrder)",
                new MapSqlParameterSource().addValue("id", colorId).addValue("goodsId", goodsId)
                    .addValue("sortOrder", index));
            jdbc.update("INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'ko', :name)",
                Map.of("id", colorId, "name", colorNames.get(index)));
            jdbc.update("INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'en', :name)",
                Map.of("id", colorId, "name", colorNamesEn.get(index)));
        }

        List<UUID> sizeIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<String> sizeLabels = List.of("M", "L");
        for (int index = 0; index < sizeIds.size(); index++) {
            UUID sizeId = sizeIds.get(index);
            jdbc.update("INSERT INTO goods_sizes (id, goods_id, sort_order) VALUES (:id, :goodsId, :sortOrder)",
                new MapSqlParameterSource().addValue("id", sizeId).addValue("goodsId", goodsId)
                    .addValue("sortOrder", index));
            jdbc.update("INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'ko', :label)",
                Map.of("id", sizeId, "label", sizeLabels.get(index)));
            jdbc.update("INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'en', :label)",
                Map.of("id", sizeId, "label", sizeLabels.get(index)));
        }

        List<UUID> combinationIds = new ArrayList<>();
        List<String> statuses = List.of("ON_SALE", "SOLD_OUT");
        for (int index = 0; index < statuses.size(); index++) {
            UUID combinationId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO goods_combinations (id, goods_id, color_id, size_id, availability, updated_at)
                VALUES (:id, :goodsId, :colorId, :sizeId, :availability, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("id", combinationId)
                .addValue("goodsId", goodsId)
                .addValue("colorId", colorIds.get(index))
                .addValue("sizeId", sizeIds.get(index))
                .addValue("availability", statuses.get(index))
                .addValue("updatedAt", updatedAt));
            combinationIds.add(combinationId);
        }
        return new GoodsFixture(goodsId, combinationIds);
    }

    private void insertTimedSingleGoods(UUID festivalId, UUID goodsId, OffsetDateTime updatedAt) {
        UUID combinationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 5000, :updatedAt, :updatedAt)
            """, new MapSqlParameterSource()
            .addValue("id", goodsId)
            .addValue("festivalId", festivalId)
            .addValue("updatedAt", updatedAt));
        insertGoodsTranslation(goodsId, "ko", "정렬 상품", null);
        insertGoodsTranslation(goodsId, "en", "Ordered Goods", null);
        jdbc.update("""
            INSERT INTO goods_combinations (id, goods_id, color_id, size_id, availability, updated_at)
            VALUES (:id, :goodsId, NULL, NULL, 'ON_SALE', :updatedAt)
            """, new MapSqlParameterSource()
            .addValue("id", combinationId)
            .addValue("goodsId", goodsId)
            .addValue("updatedAt", updatedAt));
    }

    private void insertGoodsTranslation(UUID goodsId, String locale, String name, String description) {
        jdbc.update("""
            INSERT INTO goods_translations (goods_id, locale, name, description)
            VALUES (:goodsId, :locale, :name, :description)
            """, new MapSqlParameterSource()
            .addValue("goodsId", goodsId)
            .addValue("locale", locale)
            .addValue("name", name)
            .addValue("description", description));
    }

    private GoodsFixture insertSingleGoods(UUID festivalId, String status) {
        UUID goodsId = UUID.randomUUID();
        UUID combinationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 5000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", festivalId));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'ko', '단일 상품')",
            Map.of("id", goodsId));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'en', 'Single Goods')",
            Map.of("id", goodsId));
        jdbc.update("""
            INSERT INTO goods_combinations (id, goods_id, color_id, size_id, availability, updated_at)
            VALUES (:id, :goodsId, NULL, NULL, :availability, CURRENT_TIMESTAMP)
            """, new MapSqlParameterSource()
            .addValue("id", combinationId)
            .addValue("goodsId", goodsId)
            .addValue("availability", status));
        return new GoodsFixture(goodsId, List.of(combinationId));
    }

    private JsonNode item(MvcResult result, UUID goodsId) throws Exception {
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("items");
        for (JsonNode item : items) {
            if (item.path("goodsId").asString().equals(goodsId.toString())) {
                return item;
            }
        }
        throw new AssertionError("Goods item was not returned: " + goodsId);
    }

    private JsonNode adminProductItem(MvcResult result, UUID goodsId) throws Exception {
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("items");
        for (JsonNode item : items) {
            if (item.path("id").asString().equals(goodsId.toString())) {
                return item;
            }
        }
        throw new AssertionError("Admin goods item was not returned: " + goodsId);
    }

    private List<String> statuses(JsonNode item) {
        List<String> result = new ArrayList<>();
        for (JsonNode combination : item.path("combinations")) {
            result.add(combination.path("status").asString());
        }
        return result;
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

    private long idempotencyCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_idempotency_records", Map.of(), Long.class);
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

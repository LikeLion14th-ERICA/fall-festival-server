package dev.espero.festival.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Drives the public GET /api/v2/goods* endpoints against the real goods table. */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsFlowIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");

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

    @MockitoBean
    private CatalogSnapshotProvider snapshots;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        when(snapshots.publishedLocales()).thenReturn(List.of("ko", "en", "zh-Hans", "ja"));
        jdbc.update("DELETE FROM goods_combinations", Map.of());
        jdbc.update("DELETE FROM goods_color_translations", Map.of());
        jdbc.update("DELETE FROM goods_colors", Map.of());
        jdbc.update("DELETE FROM goods_size_translations", Map.of());
        jdbc.update("DELETE FROM goods_sizes", Map.of());
        jdbc.update("DELETE FROM goods_translations", Map.of());
        jdbc.update("DELETE FROM goods", Map.of());
        jdbc.update("DELETE FROM operational_account_settings WHERE purpose = 'GOODS'", Map.of());
    }

    @Test
    void listsGoodsWithResolvedColorsAndSizes() throws Exception {
        UUID goodsId = insertOptionsGoods();

        mvc.perform(get("/api/v2/goods"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(goodsId.toString()))
            .andExpect(jsonPath("$.data.items[0].contentLocale").value("ko"))
            .andExpect(jsonPath("$.data.items[0].name").value("목 티셔츠"))
            .andExpect(jsonPath("$.data.items[0].price.amount").value(15000))
            .andExpect(jsonPath("$.data.items[0].optionMode").value("OPTIONS"))
            .andExpect(jsonPath("$.data.items[0].colors[0].name").value("검정"));

        mvc.perform(get("/api/v2/goods").param("locale", "en"))
            .andExpect(jsonPath("$.data.items[0].contentLocale").value("en"))
            .andExpect(jsonPath("$.data.items[0].name").value("Mock T-Shirt"));
    }

    @Test
    void returnsAvailabilityWithCombinationsAndAllSoldOut() throws Exception {
        UUID goodsId = insertOptionsGoods();

        mvc.perform(get("/api/v2/goods-availability").param("locale", "en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].name").value("Mock T-Shirt"));

        mvc.perform(get("/api/v2/goods/" + goodsId + "/availability"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.combinations", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.combinations[0].status").value("ON_SALE"))
            .andExpect(jsonPath("$.data.allSoldOut").value(false));

        jdbc.update("UPDATE goods_combinations SET availability = 'SOLD_OUT' WHERE goods_id = :id",
            Map.of("id", goodsId));

        mvc.perform(get("/api/v2/goods-availability"))
            .andExpect(jsonPath("$.data.items[0].allSoldOut").value(true));
    }

    @Test
    void returnsPaymentGuideAccountOnlyWhenConfigured() throws Exception {
        UUID goodsId = insertOptionsGoods();

        mvc.perform(get("/api/v2/goods/" + goodsId + "/payment-guide"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.account").doesNotExist());

        jdbc.update("""
            INSERT INTO operational_account_settings (
                festival_id, purpose, state, version, bank_name, account_number, account_holder, updated_at
            ) VALUES (
                :festivalId, 'GOODS', 'CONFIGURED', 1, '목 은행', '123-456', '목 예금주', CURRENT_TIMESTAMP
            )
            """, new MapSqlParameterSource("festivalId", FESTIVAL_ID));

        mvc.perform(get("/api/v2/goods/" + goodsId + "/payment-guide"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.account.bankName").value("목 은행"))
            .andExpect(jsonPath("$.data.transferLink").doesNotExist());
    }

    @Test
    void rejectsUnpublishedLocalesAndHidesGoodsWithIncompletePublishedTranslations() throws Exception {
        UUID goodsId = insertOptionsGoods();
        insertGoodsTranslation(goodsId, "zh-Hans", "模拟T恤");
        when(snapshots.publishedLocales()).thenReturn(List.of("ko"));

        mvc.perform(get("/api/v2/goods").param("locale", "en"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("LOCALE_NOT_READY"));

        when(snapshots.publishedLocales()).thenReturn(List.of("ko", "en", "zh-Hans", "ja"));
        mvc.perform(get("/api/v2/goods").param("locale", "zh-Hans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.empty()));
        mvc.perform(get("/api/v2/goods-availability").param("locale", "zh-Hans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.empty()));
        mvc.perform(get("/api/v2/goods/" + goodsId).param("locale", "zh-Hans"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v2/goods/" + goodsId + "/availability").param("locale", "zh-Hans"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v2/goods/" + goodsId + "/payment-guide").param("locale", "zh-Hans"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void returnsNotFoundForAnUnknownGoodsId() throws Exception {
        mvc.perform(get("/api/v2/goods/" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsAnUnknownQueryParameter() throws Exception {
        mvc.perform(get("/api/v2/goods").param("unexpected", "x"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
    }

    private UUID insertOptionsGoods() {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'OPTIONS', 15000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", FESTIVAL_ID));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'ko', '목 티셔츠')",
            Map.of("id", goodsId));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'en', 'Mock T-Shirt')",
            Map.of("id", goodsId));
        UUID colorId = UUID.randomUUID();
        jdbc.update("INSERT INTO goods_colors (id, goods_id, sort_order) VALUES (:id, :goodsId, 0)",
            Map.of("id", colorId, "goodsId", goodsId));
        jdbc.update("INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'ko', '검정')",
            Map.of("id", colorId));
        jdbc.update("INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'en', 'Black')",
            Map.of("id", colorId));
        UUID sizeId = UUID.randomUUID();
        jdbc.update("INSERT INTO goods_sizes (id, goods_id, sort_order) VALUES (:id, :goodsId, 0)",
            Map.of("id", sizeId, "goodsId", goodsId));
        jdbc.update("INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'ko', 'M')",
            Map.of("id", sizeId));
        jdbc.update("INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'en', 'M')",
            Map.of("id", sizeId));
        jdbc.update("""
            INSERT INTO goods_combinations (id, goods_id, color_id, size_id, availability, updated_at)
            VALUES (:id, :goodsId, :colorId, :sizeId, 'ON_SALE', CURRENT_TIMESTAMP)
            """, Map.of("id", UUID.randomUUID(), "goodsId", goodsId, "colorId", colorId, "sizeId", sizeId));
        return goodsId;
    }

    private void insertGoodsTranslation(UUID goodsId, String locale, String name) {
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, :locale, :name)",
            Map.of("id", goodsId, "locale", locale, "name", name));
    }
}

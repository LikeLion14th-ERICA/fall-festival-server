package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsAvailability;
import java.util.List;
import java.util.Map;
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

/** Verifies the real V21 goods schema against GoodsStore's read queries. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsStoreIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private GoodsStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @Transactional
    void findAllAssemblesTranslationsColorsSizesAndCombinationsForOptionsMode() {
        UUID goodsId = insertGoods("OPTIONS", 5000);
        UUID colorA = insertColor(goodsId, 0);
        UUID colorB = insertColor(goodsId, 1);
        UUID sizeM = insertSize(goodsId, 0);
        insertCombination(goodsId, colorA, sizeM, "ON_SALE");
        insertCombination(goodsId, colorB, sizeM, "SOLD_OUT");

        List<Goods> all = store.findAll(FESTIVAL_ID);
        Goods goods = all.stream().filter(g -> g.id().equals(goodsId)).findFirst().orElseThrow();

        assertThat(goods.translations()).containsOnlyKeys("ko", "en");
        assertThat(goods.translations().get("ko").name()).isEqualTo("상품");
        assertThat(goods.priceAmount()).isEqualTo(5000);
        assertThat(goods.colors()).extracting(c -> c.id()).containsExactly(colorA, colorB);
        assertThat(goods.sizes()).extracting(s -> s.id()).containsExactly(sizeM);
        assertThat(goods.combinations()).hasSize(2);
        assertThat(goods.combinations()).extracting(c -> c.availability())
            .containsExactlyInAnyOrder(GoodsAvailability.ON_SALE, GoodsAvailability.SOLD_OUT);
    }

    @Test
    @Transactional
    void findAllAssemblesASingleOpaqueCombinationForSingleMode() {
        UUID goodsId = insertGoods("SINGLE", 3000);
        insertCombination(goodsId, null, null, "ON_SALE");

        Goods goods = store.findById(FESTIVAL_ID, goodsId).orElseThrow();

        assertThat(goods.colors()).isEmpty();
        assertThat(goods.sizes()).isEmpty();
        assertThat(goods.combinations()).hasSize(1);
        assertThat(goods.combinations().get(0).colorId()).isNull();
        assertThat(goods.combinations().get(0).sizeId()).isNull();
    }

    @Test
    @Transactional
    void findByIdReturnsEmptyForAnotherFestivalOrUnknownId() {
        UUID goodsId = insertGoods("SINGLE", 1000);
        insertCombination(goodsId, null, null, "ON_SALE");

        assertThat(store.findById(FESTIVAL_ID, goodsId)).isPresent();
        assertThat(store.findById(UUID.randomUUID(), goodsId)).isEmpty();
        assertThat(store.findById(FESTIVAL_ID, UUID.randomUUID())).isEmpty();
    }

    private UUID insertGoods(String optionMode, long priceAmount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, :optionMode, :priceAmount, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", id, "festivalId", FESTIVAL_ID, "optionMode", optionMode, "priceAmount", priceAmount));
        jdbc.update("""
            INSERT INTO goods_translations (goods_id, locale, name, description)
            VALUES (:id, 'ko', '상품', NULL)
            """, Map.of("id", id));
        jdbc.update("""
            INSERT INTO goods_translations (goods_id, locale, name, description)
            VALUES (:id, 'en', 'Product', NULL)
            """, Map.of("id", id));
        return id;
    }

    private UUID insertColor(UUID goodsId, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods_colors (id, goods_id, sort_order) VALUES (:id, :goodsId, :sortOrder)
            """, Map.of("id", id, "goodsId", goodsId, "sortOrder", sortOrder));
        jdbc.update("""
            INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'ko', '색상')
            """, Map.of("id", id));
        jdbc.update("""
            INSERT INTO goods_color_translations (color_id, locale, name) VALUES (:id, 'en', 'Color')
            """, Map.of("id", id));
        return id;
    }

    private UUID insertSize(UUID goodsId, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods_sizes (id, goods_id, sort_order) VALUES (:id, :goodsId, :sortOrder)
            """, Map.of("id", id, "goodsId", goodsId, "sortOrder", sortOrder));
        jdbc.update("""
            INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'ko', 'M')
            """, Map.of("id", id));
        jdbc.update("""
            INSERT INTO goods_size_translations (size_id, locale, label) VALUES (:id, 'en', 'M')
            """, Map.of("id", id));
        return id;
    }

    private UUID insertCombination(UUID goodsId, UUID colorId, UUID sizeId, String availability) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods_combinations (id, goods_id, color_id, size_id, availability, updated_at)
            VALUES (:id, :goodsId, :colorId, :sizeId, :availability, CURRENT_TIMESTAMP)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", id).addValue("goodsId", goodsId).addValue("colorId", colorId)
            .addValue("sizeId", sizeId).addValue("availability", availability));
        return id;
    }
}

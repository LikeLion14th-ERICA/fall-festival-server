package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.domain.Goods;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsImageReadStoreIntegrationTest {

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
    void hydratesAttachedImagesAndTranslationsInSortOrder() {
        UUID goodsId = insertGoods();
        UUID first = insertMedia(goodsId, 0, true, false);
        UUID second = insertMedia(goodsId, 1, true, false);

        Goods goods = store.findById(FESTIVAL_ID, goodsId).orElseThrow();

        assertThat(goods.images()).extracting(image -> image.mediaId()).containsExactly(first, second);
        assertThat(goods.images().get(0).translations()).containsOnlyKeys("ko", "en");
        assertThat(goods.images().get(0).translations().get("ko").alt()).isEqualTo("한국어 대체 텍스트 0");
        assertThat(goods.images().get(1).translations().get("en").alt()).isEqualTo("English alt 1");
    }

    @Test
    @Transactional
    void excludesUnattachedAndDetachedImagesAndKeepsExistingGoodsCompatible() {
        UUID noImageGoods = insertGoods();
        UUID unattachedGoods = insertGoods();
        UUID detachedGoods = insertGoods();
        insertMedia(unattachedGoods, 0, false, false);
        insertMedia(detachedGoods, 0, true, true);

        assertThat(store.findById(FESTIVAL_ID, noImageGoods).orElseThrow().images()).isEmpty();
        assertThat(store.findById(FESTIVAL_ID, unattachedGoods).orElseThrow().images()).isEmpty();
        assertThat(store.findById(FESTIVAL_ID, detachedGoods).orElseThrow().images()).isEmpty();
    }

    private UUID insertGoods() {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", FESTIVAL_ID));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'ko', '상품')",
            Map.of("id", goodsId));
        jdbc.update("INSERT INTO goods_translations (goods_id, locale, name) VALUES (:id, 'en', 'Goods')",
            Map.of("id", goodsId));
        return goodsId;
    }

    private UUID insertMedia(UUID goodsId, int sortOrder, boolean attached, boolean detached) {
        UUID mediaId = UUID.randomUUID();
        String storageKey = "goods/" + FESTIVAL_ID + "/" + mediaId.toString().substring(0, 2) + "/" + mediaId;
        jdbc.update("""
            INSERT INTO media_assets (
                id, festival_id, purpose, storage_key, source_sha256, source_size_bytes,
                source_width, source_height, master_width, master_height,
                normalized_format, content_type, created_at, attached_at, detached_at
            ) VALUES (
                :id, :festivalId, 'GOODS_IMAGE', :storageKey, :sha, 100,
                1024, 1024, 1024, 1024, 'WEBP', 'image/webp',
                CURRENT_TIMESTAMP, CASE WHEN :attached THEN CURRENT_TIMESTAMP ELSE NULL END,
                CASE WHEN :detached THEN CURRENT_TIMESTAMP ELSE NULL END
            )
            """, new MapSqlParameterSource()
            .addValue("id", mediaId)
            .addValue("festivalId", FESTIVAL_ID)
            .addValue("storageKey", storageKey)
            .addValue("sha", "0".repeat(64))
            .addValue("attached", attached)
            .addValue("detached", detached));
        jdbc.update("""
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES (:mediaId, :festivalId, :goodsId, :sortOrder)
            """, new MapSqlParameterSource()
            .addValue("mediaId", mediaId)
            .addValue("festivalId", FESTIVAL_ID)
            .addValue("goodsId", goodsId)
            .addValue("sortOrder", sortOrder));
        jdbc.update("""
            INSERT INTO goods_image_translations (media_id, locale, alt_text)
            VALUES (:mediaId, 'ko', :ko), (:mediaId, 'en', :en)
            """, new MapSqlParameterSource()
            .addValue("mediaId", mediaId)
            .addValue("ko", "한국어 대체 텍스트 " + sortOrder)
            .addValue("en", "English alt " + sortOrder));
        return mediaId;
    }
}

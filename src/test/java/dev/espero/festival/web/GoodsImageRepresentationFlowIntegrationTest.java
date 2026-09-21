package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.auth.AdminPrincipal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsImageRepresentationFlowIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID ADMIN_ID = UUID.fromString("a4d71f22-4d36-42eb-9bde-1829936215c2");
    private static final Path MEDIA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "festival-goods-image-representation-" + UUID.randomUUID()
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("festival.media.storage-root", MEDIA_ROOT::toString);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM goods_image_translations", Map.of());
        jdbc.update("DELETE FROM goods_images", Map.of());
        jdbc.update("DELETE FROM media_assets", Map.of());
        jdbc.update("DELETE FROM goods_combinations", Map.of());
        jdbc.update("DELETE FROM goods_color_translations", Map.of());
        jdbc.update("DELETE FROM goods_colors", Map.of());
        jdbc.update("DELETE FROM goods_size_translations", Map.of());
        jdbc.update("DELETE FROM goods_sizes", Map.of());
        jdbc.update("DELETE FROM goods_translations", Map.of());
        jdbc.update("DELETE FROM goods", Map.of());
    }

    @Test
    void publicListAndDetailUseProductContentLocaleForOrderedImageAltAndCanonicalUrls() throws Exception {
        UUID goodsId = insertGoods();
        UUID first = insertImage(goodsId, 0, "앞면", "Front");
        UUID second = insertImage(goodsId, 1, "뒷면", "Back");

        mvc.perform(get("/api/v2/goods").queryParam("locale", "zh-Hans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].contentLocale").value("en"))
            .andExpect(jsonPath("$.data.items[0].images[0].alt").value("Front"))
            .andExpect(jsonPath("$.data.items[0].images[0].masterUrl")
                .value("/api/v2/media/goods-images/" + first + "/master"))
            .andExpect(jsonPath("$.data.items[0].images[0].thumbnail320Url")
                .value("/api/v2/media/goods-images/" + first + "/320"))
            .andExpect(jsonPath("$.data.items[0].images[0].thumbnail640Url")
                .value("/api/v2/media/goods-images/" + first + "/640"))
            .andExpect(jsonPath("$.data.items[0].images[1].alt").value("Back"));

        mvc.perform(get("/api/v2/goods/" + goodsId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.images", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.data.images[0].alt").value("앞면"))
            .andExpect(jsonPath("$.data.images[1].masterUrl")
                .value("/api/v2/media/goods-images/" + second + "/master"));
    }

    @Test
    void publicListAndDetailReturnAnEmptyImageArrayWhenNoImageIsAssociated() throws Exception {
        UUID goodsId = insertGoods();

        mvc.perform(get("/api/v2/goods"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].images", org.hamcrest.Matchers.empty()));
        mvc.perform(get("/api/v2/goods/" + goodsId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.images", org.hamcrest.Matchers.empty()));
    }

    @Test
    void adminListAndDetailExposeFullAltShapeAndImagesChangeTheDetailEtag() throws Exception {
        UUID goodsId = insertGoods();
        String before = mvc.perform(asAdmin(get("/api/v2/admin/products/" + goodsId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.images", org.hamcrest.Matchers.empty()))
            .andReturn().getResponse().getHeader("ETag");

        UUID mediaId = insertImage(goodsId, 0, "앞면", "Front");

        mvc.perform(asAdmin(get("/api/v2/admin/products")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].images[0].mediaId").value(mediaId.toString()))
            .andExpect(jsonPath("$.data.items[0].images[0].alt.ko").value("앞면"))
            .andExpect(jsonPath("$.data.items[0].images[0].alt.en").value("Front"))
            .andExpect(jsonPath("$.data.items[0].images[0].alt.zh-Hans").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].images[0].alt.ja").value(org.hamcrest.Matchers.nullValue()));

        MvcResult after = mvc.perform(asAdmin(get("/api/v2/admin/products/" + goodsId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.images[0].thumbnail640Url")
                .value("/api/v2/media/goods-images/" + mediaId + "/640"))
            .andReturn();
        assertThat(after.getResponse().getHeader("ETag")).isNotEqualTo(before);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(
            new AdminPrincipal(ADMIN_ID, "goods-admin", "ADMIN"),
            null,
            List.of(new SimpleGrantedAuthority("ADMIN"))
        )));
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

    private UUID insertImage(UUID goodsId, int sortOrder, String koAlt, String enAlt) {
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
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
            )
            """, new MapSqlParameterSource()
            .addValue("id", mediaId).addValue("festivalId", FESTIVAL_ID)
            .addValue("storageKey", storageKey).addValue("sha", "1".repeat(64)));
        jdbc.update("""
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES (:mediaId, :festivalId, :goodsId, :sortOrder)
            """, new MapSqlParameterSource()
            .addValue("mediaId", mediaId).addValue("festivalId", FESTIVAL_ID)
            .addValue("goodsId", goodsId).addValue("sortOrder", sortOrder));
        jdbc.update("""
            INSERT INTO goods_image_translations (media_id, locale, alt_text)
            VALUES (:mediaId, 'ko', :ko), (:mediaId, 'en', :en)
            """, new MapSqlParameterSource()
            .addValue("mediaId", mediaId).addValue("ko", koAlt).addValue("en", enAlt));
        return mediaId;
    }
}

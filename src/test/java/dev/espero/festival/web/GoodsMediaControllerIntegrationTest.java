package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.media.MediaStorage;
import dev.espero.festival.media.MediaVariant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsMediaControllerIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID OTHER_FESTIVAL_ID = UUID.fromString("4b028799-51c2-43fb-943c-f55ec5c60d99");
    private static final Path MEDIA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "festival-goods-media-read-" + UUID.randomUUID()
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @Autowired
    private MediaStorage mediaStorage;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
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
        jdbc.update("DELETE FROM festivals WHERE id = :id", Map.of("id", OTHER_FESTIVAL_ID));
        jdbc.update("""
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES (:id, 'Other festival', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", OTHER_FESTIVAL_ID));
        deleteTree(MEDIA_ROOT);
        Files.createDirectories(MEDIA_ROOT.resolve(".staging"));
    }

    @AfterAll
    static void removeMediaRoot() throws IOException {
        deleteTree(MEDIA_ROOT);
    }

    @Test
    void anonymouslyStreamsEveryAttachedVariantWithImmutableHeaders() throws Exception {
        UUID mediaId = insertAttachedMediaWithFiles(FESTIVAL_ID);

        for (String variant : new String[]{"master", "320", "640"}) {
            byte[] expected = bytes(variant);
            MvcResult result = performStreaming(route(mediaId, variant))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", GoodsMediaController.CACHE_CONTROL))
                .andExpect(header().string("ETag", org.hamcrest.Matchers.matchesPattern("\"[0-9a-f]{64}\"")))
                .andExpect(content().bytes(expected))
                .andReturn();
            assertThat(result.getResponse().getHeader("ETag")).isNotBlank();
        }
    }

    @Test
    void supportsStrongWeakAndWildcardConditionalRequestsPerVariant() throws Exception {
        UUID mediaId = insertAttachedMediaWithFiles(FESTIVAL_ID);
        String masterEtag = performStreaming(route(mediaId, "master")).andReturn().getResponse().getHeader("ETag");
        String thumbEtag = performStreaming(route(mediaId, "320")).andReturn().getResponse().getHeader("ETag");
        assertThat(masterEtag).isNotEqualTo(thumbEtag);

        for (String candidate : new String[]{masterEtag, "W/" + masterEtag, "*"}) {
            mvc.perform(get(route(mediaId, "master")).header("If-None-Match", candidate))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", masterEtag))
                .andExpect(header().string("Cache-Control", GoodsMediaController.CACHE_CONTROL))
                .andExpect(content().string(""));
        }

        performStreaming(get(route(mediaId, "master")).header("If-None-Match", thumbEtag))
            .andExpect(status().isOk())
            .andExpect(content().bytes(bytes("master")));
    }

    @Test
    void hidesUnattachedDetachedOtherFestivalAndUnknownMedia() throws Exception {
        UUID unattached = insertAssociatedMedia(FESTIVAL_ID, false, false);
        UUID detached = insertAssociatedMedia(FESTIVAL_ID, true, true);
        UUID otherFestival = insertAttachedMediaWithFiles(OTHER_FESTIVAL_ID);

        for (UUID mediaId : new UUID[]{unattached, detached, otherFestival, UUID.randomUUID()}) {
            mvc.perform(get(route(mediaId, "master")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
    }

    @Test
    void rejectsUnknownVariantAndEveryQueryParameter() throws Exception {
        UUID mediaId = insertAttachedMediaWithFiles(FESTIVAL_ID);

        mvc.perform(get(route(mediaId, "original")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get(route(mediaId, "master")).queryParam("download", "true"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(get(route(mediaId, "master")).queryParam("foo", "bar"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
    }

    @Test
    void reportsAttachedDatabaseRowsWithMissingStoredVariantsAsRetryableUnavailable() throws Exception {
        UUID mediaId = insertAssociatedMedia(FESTIVAL_ID, true, false);

        MvcResult result = mvc.perform(get(route(mediaId, "master")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.error.retryable").value(true))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(MEDIA_ROOT.toString());
    }

    private org.springframework.test.web.servlet.ResultActions performStreaming(String path) throws Exception {
        return performStreaming(get(path));
    }

    private org.springframework.test.web.servlet.ResultActions performStreaming(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requestBuilder
    ) throws Exception {
        MvcResult initial = mvc.perform(requestBuilder)
            .andExpect(request().asyncStarted())
            .andReturn();
        return mvc.perform(asyncDispatch(initial));
    }

    private UUID insertAttachedMediaWithFiles(UUID festivalId) throws Exception {
        UUID mediaId = insertAssociatedMedia(festivalId, true, false);
        UUID operationId = UUID.randomUUID();
        mediaStorage.createStaging(operationId);
        for (MediaVariant variant : MediaVariant.values()) {
            try (var output = mediaStorage.openStagingOutput(operationId, variant)) {
                output.write(bytes(switch (variant) {
                    case MASTER -> "master";
                    case THUMB_320 -> "320";
                    case THUMB_640 -> "640";
                }));
            }
        }
        mediaStorage.finalizeStaging(operationId, festivalId, mediaId);
        return mediaId;
    }

    private UUID insertAssociatedMedia(UUID festivalId, boolean attached, boolean detached) {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", festivalId));
        UUID mediaId = UUID.randomUUID();
        String storageKey = "goods/" + festivalId + "/" + mediaId.toString().substring(0, 2) + "/" + mediaId;
        jdbc.update("""
            INSERT INTO media_assets (
                id, festival_id, purpose, storage_key, source_sha256, source_size_bytes,
                source_width, source_height, master_width, master_height,
                normalized_format, content_type, created_at, attached_at, detached_at
            ) VALUES (
                :id, :festivalId, 'GOODS_IMAGE', :storageKey, :sha, 100,
                1024, 1024, 1024, 1024, 'WEBP', 'image/webp', CURRENT_TIMESTAMP,
                CASE WHEN :attached THEN CURRENT_TIMESTAMP ELSE NULL END,
                CASE WHEN :detached THEN CURRENT_TIMESTAMP ELSE NULL END
            )
            """, new MapSqlParameterSource()
            .addValue("id", mediaId).addValue("festivalId", festivalId)
            .addValue("storageKey", storageKey).addValue("sha", "2".repeat(64))
            .addValue("attached", attached).addValue("detached", detached));
        jdbc.update("""
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES (:mediaId, :festivalId, :goodsId, 0)
            """, new MapSqlParameterSource()
            .addValue("mediaId", mediaId).addValue("festivalId", festivalId).addValue("goodsId", goodsId));
        return mediaId;
    }

    private static byte[] bytes(String variant) {
        return ("webp-" + variant).getBytes(StandardCharsets.UTF_8);
    }

    private static String route(UUID mediaId, String variant) {
        return "/api/v2/media/goods-images/" + mediaId + "/" + variant;
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

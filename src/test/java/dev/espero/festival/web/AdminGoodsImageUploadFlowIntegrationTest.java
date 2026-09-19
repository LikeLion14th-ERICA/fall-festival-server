package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.media.GoodsImageFormat;
import dev.espero.festival.media.GoodsImageInspection;
import dev.espero.festival.media.GoodsImageProcessor;
import dev.espero.festival.media.MediaProcessingBusyException;
import dev.espero.festival.media.MediaStorage;
import dev.espero.festival.media.MediaVariant;
import dev.espero.festival.media.ProcessedGoodsImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.ConfigurableSmartRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises the administrator upload orchestration without external codec binaries. */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminGoodsImageUploadFlowIntegrationTest {

    private static final String ROUTE = "/api/v2/admin/media/goods-images";
    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID ADMIN_ID = UUID.fromString("a4d71f22-4d36-42eb-9bde-1829936215c2");
    private static final Path MEDIA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "festival-goods-upload-flow-" + UUID.randomUUID()
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
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @MockitoBean
    private GoodsImageProcessor processor;

    @MockitoSpyBean
    private MediaStorage mediaStorage;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clock.set("2030-10-01T12:00:00+09:00");
        jdbc.update("DELETE FROM goods_image_translations", Map.of());
        jdbc.update("DELETE FROM goods_images", Map.of());
        jdbc.update("DELETE FROM media_assets", Map.of());
        jdbc.update("DELETE FROM admin_idempotency_records", Map.of());
        jdbc.update("DELETE FROM admin_audit_events", Map.of());
        jdbc.update("DELETE FROM admin_refresh_sessions", Map.of());
        jdbc.update("DELETE FROM admin_accounts", Map.of());
        jdbc.update("""
            INSERT INTO admin_accounts (
                id, username, password_hash, authority, enabled, created_at, updated_at, last_login_at
            ) VALUES (
                :id, 'media-admin', 'test-only-password-hash', 'ADMIN', true,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
            )
            """, Map.of("id", ADMIN_ID));
        clearMediaRoot();
        Files.createDirectories(MEDIA_ROOT.resolve(".staging"));
        stubSuccessfulProcessing();
    }

    @AfterAll
    static void removeMediaRoot() throws IOException {
        deleteTree(MEDIA_ROOT);
    }

    @Test
    void uploadsAndReplaysTheSameImageWithOneMutationAndFreshMeta() throws Exception {
        byte[] png = png(Color.BLUE);

        MvcResult first = mvc.perform(asAdmin(upload(png, "same-file-key"))
                .header("X-Request-Id", "client-request-one"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.mediaId").isString())
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.locale").value("ko"))
            .andReturn();
        clock.advance(Duration.ofSeconds(2));
        MvcResult second = mvc.perform(asAdmin(upload(png, "same-file-key"))
                .header("X-Request-Id", "client-request-two"))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode firstBody = body(first);
        JsonNode secondBody = body(second);
        UUID mediaId = UUID.fromString(firstBody.path("data").path("mediaId").asString());
        assertThat(secondBody.path("data").path("mediaId").asString()).isEqualTo(mediaId.toString());
        assertThat(secondBody.path("meta").path("requestId").asString())
            .isNotEqualTo(firstBody.path("meta").path("requestId").asString());
        assertThat(secondBody.path("meta").path("serverTime").asString())
            .isNotEqualTo(firstBody.path("meta").path("serverTime").asString());

        Map<String, Object> asset = jdbc.queryForMap("""
            SELECT purpose, storage_key, normalized_format, content_type, attached_at, detached_at
            FROM media_assets WHERE id = :id
            """, Map.of("id", mediaId));
        assertThat(asset.get("purpose")).isEqualTo("GOODS_IMAGE");
        assertThat(asset.get("storage_key").toString()).doesNotContain("client-secret-name");
        assertThat(asset.get("normalized_format")).isEqualTo("WEBP");
        assertThat(asset.get("content_type")).isEqualTo("image/webp");
        assertThat(asset.get("attached_at")).isNull();
        assertThat(asset.get("detached_at")).isNull();
        for (MediaVariant variant : MediaVariant.values()) {
            try (var input = mediaStorage.open(FESTIVAL_ID, mediaId, variant)) {
                assertThat(input.readAllBytes()).isNotEmpty();
            }
        }
        assertThat(mediaAssetCount()).isOne();
        assertThat(auditCount()).isOne();
        assertThat(regularMediaFileCount()).isEqualTo(3);
        assertThat(stagingEntryCount()).isZero();
        Map<String, Object> audit = jdbc.queryForMap(
            "SELECT action, resource_type, resource_id FROM admin_audit_events",
            Map.of()
        );
        assertThat(audit).containsEntry("action", "GOODS_IMAGE_UPLOADED")
            .containsEntry("resource_type", "MEDIA")
            .containsEntry("resource_id", mediaId.toString());
        verify(processor, times(2)).process(any(), any(), any());
    }

    @Test
    void rejectsReuseOfAKeyForDifferentSourceBytesAndCleansSecondStaging() throws Exception {
        mvc.perform(asAdmin(upload(png(Color.BLUE), "reused-key")))
            .andExpect(status().isCreated());

        mvc.perform(asAdmin(upload(png(Color.RED), "reused-key")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(mediaAssetCount()).isOne();
        assertThat(auditCount()).isOne();
        assertThat(regularMediaFileCount()).isEqualTo(3);
        assertThat(stagingEntryCount()).isZero();
    }

    @Test
    void requiresAValidIdempotencyKeyBeforeProcessing() throws Exception {
        byte[] png = png(Color.BLUE);

        mvc.perform(asAdmin(multipart(ROUTE)
                .file(file(png))))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(asAdmin(upload(png, "invalid key")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertNoPersistentState();
    }

    @Test
    void enforcesAuthenticationNoQueryAndMultipartContentType() throws Exception {
        byte[] png = png(Color.BLUE);

        mvc.perform(upload(png, "anonymous-key"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(asAdmin(upload(png, "query-key").queryParam("foo", "bar")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(asAdmin(post(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "json-key")
                .content("{}")))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertNoPersistentState();
    }

    @Test
    void rejectsUnsupportedBytesAndEveryMalformedMultipartShape() throws Exception {
        mvc.perform(asAdmin(upload("not-an-image".getBytes(StandardCharsets.UTF_8), "bad-image-key")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(asAdmin(multipart(ROUTE).header("Idempotency-Key", "missing-file-key")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(asAdmin(multipart(ROUTE)
                .file(file(new byte[0]))
                .header("Idempotency-Key", "empty-file-key")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(asAdmin(multipart(ROUTE)
                .file(file(png(Color.BLUE)))
                .file(new MockMultipartFile("extra", "extra.png", "image/png", png(Color.RED)))
                .header("Idempotency-Key", "extra-file-key")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(asAdmin(multipart(ROUTE)
                .file(file(png(Color.BLUE)))
                .file(file(png(Color.RED)))
                .header("Idempotency-Key", "multiple-file-key")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(asAdmin(upload(png(Color.BLUE), "extra-field-key").param("note", "extra")))
            .andExpect(status().isUnprocessableEntity());

        assertNoPersistentState();
    }

    @Test
    void mapsProcessorBackPressureWithoutSideEffects() throws Exception {
        doThrow(new MediaProcessingBusyException(Duration.ofSeconds(1)))
            .when(processor).process(any(), any(), any());

        mvc.perform(asAdmin(upload(png(Color.BLUE), "busy-key")))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.error.retryable").value(true));

        assertNoPersistentState();
        assertThat(stagingEntryCount()).isZero();
    }

    @Test
    void removesFinalizedFilesWhenTheDatabaseMutationRollsBack() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IOException("simulated failure after atomic move");
        }).when(mediaStorage).finalizeStaging(any(), any(), any());

        mvc.perform(asAdmin(upload(png(Color.BLUE), "finalization-failure-key")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.error.retryable").value(true));

        assertNoPersistentState();
        assertThat(regularMediaFileCount()).isZero();
        assertThat(stagingEntryCount()).isZero();
    }

    private void stubSuccessfulProcessing() throws Exception {
        doAnswer(invocation -> {
            GoodsImageInspection inspection = invocation.getArgument(1);
            UUID operationId = invocation.getArgument(2);
            mediaStorage.createStaging(operationId);
            for (MediaVariant variant : MediaVariant.values()) {
                try (var output = mediaStorage.openStagingOutput(operationId, variant)) {
                    output.write(("deterministic-webp-" + variant.name()).getBytes(StandardCharsets.US_ASCII));
                }
            }
            return new ProcessedGoodsImage(
                inspection.sourceSha256(),
                inspection.sourceSizeBytes(),
                GoodsImageFormat.PNG,
                inspection.sourceWidth(),
                inspection.sourceHeight(),
                1024,
                1024
            );
        }).when(processor).process(any(), any(), any());
    }

    private MockMultipartHttpServletRequestBuilder upload(byte[] content, String key) {
        return multipart(ROUTE)
            .file(file(content))
            .header("Idempotency-Key", key);
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile(
            "file",
            "../../client-secret-name.png",
            "application/octet-stream",
            content
        );
    }

    private <B extends ConfigurableSmartRequestBuilder<B>> B asAdmin(B request) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
            new AdminPrincipal(ADMIN_ID, "media-admin", "ADMIN"),
            null,
            java.util.List.of(new SimpleGrantedAuthority("ADMIN"))
        )));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long mediaAssetCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM media_assets", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private long auditCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_audit_events", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private void assertNoPersistentState() {
        assertThat(mediaAssetCount()).isZero();
        assertThat(auditCount()).isZero();
    }

    private long stagingEntryCount() throws IOException {
        try (var paths = Files.list(MEDIA_ROOT.resolve(".staging"))) {
            return paths.count();
        }
    }

    private long regularMediaFileCount() throws IOException {
        if (Files.notExists(MEDIA_ROOT)) {
            return 0;
        }
        try (var paths = Files.walk(MEDIA_ROOT)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static byte[] png(Color color) throws IOException {
        BufferedImage image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG test encoder is unavailable");
            }
            return output.toByteArray();
        }
    }

    private static void clearMediaRoot() throws IOException {
        deleteTree(MEDIA_ROOT);
        Files.createDirectories(MEDIA_ROOT);
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

        void advance(Duration duration) {
            instant = instant.plus(duration);
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

package dev.espero.festival.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.media.MediaStorage;
import dev.espero.festival.media.MediaVariant;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsMediaCleanupIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final Instant NOW = Instant.parse("2030-10-02T03:00:00Z");
    private static final Path MEDIA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "festival-goods-media-cleanup-" + UUID.randomUUID()
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("festival.cleanup.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("festival.cleanup.datasource.username", POSTGRES::getUsername);
        registry.add("festival.cleanup.datasource.password", POSTGRES::getPassword);
        registry.add("festival.cleanup.datasource.role", POSTGRES::getUsername);
        registry.add("festival.media.storage-root", MEDIA_ROOT::toString);
    }

    @Autowired
    private CleanupJob job;

    @Autowired
    private CleanupProperties properties;

    @Autowired
    private CleanupDataSourceProvider dataSourceProvider;

    @Autowired
    private GoodsUnattachedMediaCleanupTarget unattachedTarget;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private MediaStorage mediaStorage;

    @Autowired
    private MutableClock clock;

    private int originalBatchSize;

    @BeforeEach
    void setUp() throws IOException {
        originalBatchSize = properties.getBatchSize();
        properties.setBatchSize(500);
        properties.getDatasource().setRole(POSTGRES.getUsername());
        clock.set(NOW);
        jdbc.update("DELETE FROM goods_image_translations", Map.of());
        jdbc.update("DELETE FROM goods_images", Map.of());
        jdbc.update("DELETE FROM media_assets", Map.of());
        jdbc.update("DELETE FROM goods_combinations", Map.of());
        jdbc.update("DELETE FROM goods_translations", Map.of());
        jdbc.update("DELETE FROM goods", Map.of());
        deleteTree(MEDIA_ROOT);
        Files.createDirectories(MEDIA_ROOT.resolve(".staging"));
    }

    @AfterEach
    void restoreProperties() {
        properties.setBatchSize(originalBatchSize);
    }

    @AfterAll
    static void removeMediaRoot() throws IOException {
        deleteTree(MEDIA_ROOT);
    }

    @Test
    void appliesExactCutoffsDeletesEligibleFilesAndProtectsActiveAssociations() throws Exception {
        UUID recentUnattached = insertMedia(NOW.minus(Duration.ofHours(23)).minus(Duration.ofMinutes(59)), null, null, false);
        UUID exactUnattached = insertMedia(NOW.minus(Duration.ofHours(24)), null, null, false);
        UUID oldUnattached = insertMedia(NOW.minus(Duration.ofHours(24)).minusSeconds(1), null, null, true);
        UUID associatedUnattached = insertMedia(NOW.minus(Duration.ofDays(2)), null, null, true);
        associate(associatedUnattached);

        Instant attachedAt = NOW.minus(Duration.ofDays(4));
        UUID recentDetached = insertMedia(attachedAt, attachedAt, NOW.minus(Duration.ofHours(23)).minus(Duration.ofMinutes(59)), false);
        UUID exactDetached = insertMedia(attachedAt, attachedAt, NOW.minus(Duration.ofDays(1)), false);
        UUID oldDetached = insertMedia(attachedAt, attachedAt, NOW.minus(Duration.ofDays(1)).minusSeconds(1), true);
        UUID associatedDetached = insertMedia(attachedAt, attachedAt, NOW.minus(Duration.ofDays(2)), true);
        associate(associatedDetached);

        CleanupRunResult result = job.run(CleanupMode.DELETE);

        assertThat(exists(oldUnattached)).isFalse();
        assertThat(exists(oldDetached)).isFalse();
        assertThat(mediaDirectory(oldUnattached)).doesNotExist();
        assertThat(mediaDirectory(oldDetached)).doesNotExist();
        assertThat(List.of(recentUnattached, exactUnattached, associatedUnattached,
            recentDetached, exactDetached, associatedDetached)).allMatch(this::exists);
        assertThat(mediaDirectory(associatedUnattached)).exists();
        assertThat(mediaDirectory(associatedDetached)).exists();
        assertThat(target(result, "goods_unattached_media").eligibleCount()).isOne();
        assertThat(target(result, "goods_detached_media").eligibleCount()).isOne();
        assertThat(result.postCommit()).hasSize(2).allMatch(CleanupPostCommitResult::succeeded);
    }

    @Test
    void treatsAMissingPhysicalDirectoryAsAnIdempotentSuccessfulDelete() {
        Instant attachedAt = NOW.minus(Duration.ofDays(4));
        UUID mediaId = insertMedia(attachedAt, attachedAt, NOW.minus(Duration.ofDays(2)), false);

        CleanupRunResult result = job.run(CleanupMode.DELETE);

        assertThat(exists(mediaId)).isFalse();
        assertThat(result.postCommit()).containsExactly(
            new CleanupPostCommitResult("goods_detached_media", 1, true)
        );
    }

    @Test
    void dryRunCountsBothLifecyclesWithoutDeletingRowsOrFiles() throws Exception {
        UUID unattached = insertMedia(NOW.minus(Duration.ofDays(2)), null, null, true);
        Instant attachedAt = NOW.minus(Duration.ofDays(4));
        UUID detached = insertMedia(attachedAt, attachedAt, NOW.minus(Duration.ofDays(2)), true);

        CleanupRunResult result = job.run(CleanupMode.DRY_RUN);

        assertThat(target(result, "goods_unattached_media").eligibleCount()).isOne();
        assertThat(target(result, "goods_detached_media").eligibleCount()).isOne();
        assertThat(target(result, "goods_unattached_media").deletedCount()).isZero();
        assertThat(target(result, "goods_detached_media").deletedCount()).isZero();
        assertThat(result.postCommit()).isEmpty();
        assertThat(exists(unattached)).isTrue();
        assertThat(exists(detached)).isTrue();
        assertThat(mediaDirectory(unattached)).exists();
        assertThat(mediaDirectory(detached)).exists();
    }

    @Test
    void oneInvocationDeletesAtMostTheConfiguredBatch() throws Exception {
        properties.setBatchSize(2);
        UUID first = insertMedia(NOW.minus(Duration.ofDays(4)), null, null, true);
        UUID second = insertMedia(NOW.minus(Duration.ofDays(3)), null, null, true);
        UUID third = insertMedia(NOW.minus(Duration.ofDays(2)), null, null, true);

        CleanupRunResult result = job.run(CleanupMode.DELETE);

        assertThat(target(result, "goods_unattached_media").eligibleCount()).isEqualTo(3);
        assertThat(target(result, "goods_unattached_media").deletedCount()).isEqualTo(2);
        assertThat(exists(first)).isFalse();
        assertThat(exists(second)).isFalse();
        assertThat(exists(third)).isTrue();
        assertThat(mediaDirectory(first)).doesNotExist();
        assertThat(mediaDirectory(second)).doesNotExist();
        assertThat(mediaDirectory(third)).exists();
    }

    @Test
    void rollbackKeepsTheDatabaseRowAndPhysicalFiles() throws Exception {
        UUID mediaId = insertMedia(NOW.minus(Duration.ofDays(2)), null, null, true);
        CleanupTarget failingTarget = new CleanupTarget() {
            @Override
            public String name() {
                return "zz_fail_after_goods_media";
            }

            @Override
            public CleanupTargetResult run(CleanupTargetContext context) {
                throw new IllegalStateException("force cleanup transaction rollback");
            }
        };
        CleanupJob rollbackJob = new CleanupJob(
            properties,
            dataSourceProvider,
            List.of(unattachedTarget, failingTarget),
            clock
        );

        assertThatThrownBy(() -> rollbackJob.run(CleanupMode.DELETE))
            .isInstanceOf(IllegalStateException.class);
        assertThat(exists(mediaId)).isTrue();
        assertThat(mediaDirectory(mediaId)).exists();
        assertStoredVariants(mediaId);
    }

    private UUID insertMedia(
        Instant createdAt,
        Instant attachedAt,
        Instant detachedAt,
        boolean withFiles
    ) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO media_assets (
                id, festival_id, purpose, storage_key, source_sha256, source_size_bytes,
                source_width, source_height, master_width, master_height,
                normalized_format, content_type, created_at, attached_at, detached_at
            ) VALUES (
                :id, :festivalId, 'GOODS_IMAGE', :storageKey, :sha, 100,
                1024, 1024, 1024, 1024, 'WEBP', 'image/webp',
                :createdAt, :attachedAt, :detachedAt
            )
            """, new MapSqlParameterSource()
            .addValue("id", mediaId)
            .addValue("festivalId", FESTIVAL_ID)
            .addValue("storageKey", mediaStorage.storageKey(FESTIVAL_ID, mediaId))
            .addValue("sha", mediaId.toString().replace("-", "") + mediaId.toString().replace("-", ""))
            .addValue("createdAt", atUtc(createdAt))
            .addValue("attachedAt", attachedAt == null ? null : atUtc(attachedAt))
            .addValue("detachedAt", detachedAt == null ? null : atUtc(detachedAt)));
        if (withFiles) {
            try {
                createStoredVariants(mediaId);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return mediaId;
    }

    private void associate(UUID mediaId) {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", FESTIVAL_ID));
        jdbc.update("""
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES (:mediaId, :festivalId, :goodsId, 0)
            """, Map.of("mediaId", mediaId, "festivalId", FESTIVAL_ID, "goodsId", goodsId));
    }

    private void createStoredVariants(UUID mediaId) throws IOException {
        UUID operationId = UUID.randomUUID();
        mediaStorage.createStaging(operationId);
        for (MediaVariant variant : MediaVariant.values()) {
            try (var output = mediaStorage.openStagingOutput(operationId, variant)) {
                output.write(("cleanup-" + variant.name()).getBytes(StandardCharsets.UTF_8));
            }
        }
        mediaStorage.finalizeStaging(operationId, FESTIVAL_ID, mediaId);
    }

    private void assertStoredVariants(UUID mediaId) throws IOException {
        for (MediaVariant variant : MediaVariant.values()) {
            try (var input = mediaStorage.open(FESTIVAL_ID, mediaId, variant)) {
                assertThat(input.readAllBytes()).isNotEmpty();
            }
        }
    }

    private CleanupTargetResult target(CleanupRunResult result, String targetName) {
        return result.targets().stream()
            .filter(target -> target.target().equals(targetName))
            .findFirst()
            .orElseThrow();
    }

    private boolean exists(UUID mediaId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM media_assets WHERE id = :id",
            Map.of("id", mediaId),
            Long.class
        );
        return count != null && count == 1;
    }

    private Path mediaDirectory(UUID mediaId) {
        return MEDIA_ROOT.resolve("goods").resolve(FESTIVAL_ID.toString())
            .resolve(mediaId.toString().replace("-", "").substring(0, 2))
            .resolve(mediaId.toString());
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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

        private volatile Instant instant = NOW;

        void set(Instant value) {
            instant = value;
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

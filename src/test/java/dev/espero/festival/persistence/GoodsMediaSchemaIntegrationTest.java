package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the V24 goods media schema against PostgreSQL. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsMediaSchemaIntegrationTest {

    private static final String SHA256 = "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void acceptsNormalizedMediaAssociationAndTranslations() {
        UUID festivalId = insertFestival();
        UUID goodsId = insertGoods(festivalId);
        UUID mediaId = insertMedia(festivalId, 4096, 4096, 2048, null, null);

        insertAssociation(mediaId, festivalId, goodsId, 0);
        insertTranslation(mediaId, "ko", "상품 앞면");
        insertTranslation(mediaId, "en", "Product front");

        assertThat(scalar("SELECT count(*) FROM media_assets WHERE id = :id", Map.of("id", mediaId)))
            .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM goods_images WHERE media_id = :id", Map.of("id", mediaId)))
            .isEqualTo(1);
        assertThat(scalar(
            "SELECT count(*) FROM goods_image_translations WHERE media_id = :id",
            Map.of("id", mediaId)
        )).isEqualTo(2);
    }

    @Test
    void compositeForeignKeysRejectCrossFestivalAssociations() {
        UUID festivalA = insertFestival();
        UUID festivalB = insertFestival();
        UUID mediaA = insertMedia(festivalA, 1024, 1024, 1024, null, null);
        UUID goodsB = insertGoods(festivalB);

        assertViolation(() -> insertAssociation(mediaA, festivalA, goodsB, 0));
        assertViolation(() -> insertAssociation(mediaA, festivalB, goodsB, 0));
    }

    @Test
    void oneMediaCannotBelongToTwoGoods() {
        UUID festivalId = insertFestival();
        UUID firstGoods = insertGoods(festivalId);
        UUID secondGoods = insertGoods(festivalId);
        UUID mediaId = insertMedia(festivalId, 1024, 1024, 1024, null, null);

        insertAssociation(mediaId, festivalId, firstGoods, 0);

        assertViolation(() -> insertAssociation(mediaId, festivalId, secondGoods, 0));
    }

    @Test
    void orderingAllowsTwoImagesAndRejectsDuplicateOrThirdPositions() {
        UUID festivalId = insertFestival();
        UUID goodsId = insertGoods(festivalId);
        UUID first = insertMedia(festivalId, 1024, 1024, 1024, null, null);
        UUID second = insertMedia(festivalId, 1024, 1024, 1024, null, null);
        UUID duplicate = insertMedia(festivalId, 1024, 1024, 1024, null, null);
        UUID third = insertMedia(festivalId, 1024, 1024, 1024, null, null);

        insertAssociation(first, festivalId, goodsId, 0);
        insertAssociation(second, festivalId, goodsId, 1);

        assertViolation(() -> insertAssociation(duplicate, festivalId, goodsId, 1));
        assertViolation(() -> insertAssociation(third, festivalId, goodsId, 2));
    }

    @Test
    void lifecycleRejectsDetachedWithoutAttachmentAndInvalidTimestampOrder() {
        UUID festivalId = insertFestival();
        OffsetDateTime created = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
        OffsetDateTime attached = created.plusHours(1);

        assertViolation(() -> insertMedia(
            UUID.randomUUID(), festivalId, 1024, 1024, 1024, created, null, created.plusHours(1)
        ));
        assertViolation(() -> insertMedia(
            UUID.randomUUID(), festivalId, 1024, 1024, 1024, created, attached, created.plusMinutes(30)
        ));
        assertViolation(() -> insertMedia(
            UUID.randomUUID(), festivalId, 1024, 1024, 1024, created, created.minusMinutes(1), null
        ));
    }

    @Test
    void dimensionsAcceptBoundariesAndRejectOutOfRangeOrNonSquareValues() {
        UUID festivalId = insertFestival();
        insertMedia(festivalId, 1024, 1024, 1024, null, null);
        insertMedia(festivalId, 4096, 4096, 2048, null, null);

        assertViolation(() -> insertMedia(festivalId, 1023, 1023, 1024, null, null));
        assertViolation(() -> insertMedia(festivalId, 4097, 4097, 2048, null, null));
        assertViolation(() -> insertMedia(festivalId, 2048, 2047, 1024, null, null));
        assertViolation(() -> insertMedia(festivalId, 4096, 4096, 2049, null, null));
        assertViolation(() -> insertMedia(festivalId, 1024, 1024, 2048, null, null));
    }

    @Test
    void translationsRejectUnknownLocaleAndBlankAltThenCascadeOnAssociationDelete() {
        UUID festivalId = insertFestival();
        UUID goodsId = insertGoods(festivalId);
        UUID mediaId = insertMedia(festivalId, 1024, 1024, 1024, null, null);
        insertAssociation(mediaId, festivalId, goodsId, 0);
        insertTranslation(mediaId, "ko", "상품 이미지");

        assertViolation(() -> insertTranslation(mediaId, "fr", "Produit"));
        assertViolation(() -> insertTranslation(mediaId, "en", " "));

        assertViolation(() -> jdbc.update("DELETE FROM goods WHERE id = :id", Map.of("id", goodsId)));
        jdbc.update("DELETE FROM goods_images WHERE media_id = :id", Map.of("id", mediaId));
        assertThat(scalar(
            "SELECT count(*) FROM goods_image_translations WHERE media_id = :id",
            Map.of("id", mediaId)
        )).isZero();
        assertThat(jdbc.update("DELETE FROM goods WHERE id = :id", Map.of("id", goodsId))).isEqualTo(1);
    }

    @Test
    void rejectsUnsafeOrNonNormalizedMediaMetadata() {
        UUID festivalId = insertFestival();
        UUID mediaId = UUID.randomUUID();
        MapSqlParameterSource unsafeStorageKey = mediaParameters(
            mediaId, festivalId, 1024, 1024, 1024,
            OffsetDateTime.now(ZoneOffset.UTC), null, null
        );

        unsafeStorageKey.addValue("storageKey", "../outside");
        assertViolation(() -> jdbc.update(mediaInsertSql(), unsafeStorageKey));

        MapSqlParameterSource invalidPurpose = mediaParameters(
            UUID.randomUUID(), festivalId, 1024, 1024, 1024,
            OffsetDateTime.now(ZoneOffset.UTC), null, null
        ).addValue("purpose", "PROFILE_IMAGE");
        assertViolation(() -> jdbc.update(mediaInsertSql(), invalidPurpose));

        MapSqlParameterSource oversized = mediaParameters(
            UUID.randomUUID(), festivalId, 1024, 1024, 1024,
            OffsetDateTime.now(ZoneOffset.UTC), null, null
        ).addValue("sourceSizeBytes", 10_485_761L);
        assertViolation(() -> jdbc.update(mediaInsertSql(), oversized));
    }

    @Test
    void createsCleanupPartialIndexes() {
        assertThat(scalar("""
            SELECT count(*) FROM pg_indexes
            WHERE schemaname = current_schema()
              AND indexname IN ('ix_media_assets_unattached_cleanup', 'ix_media_assets_detached_cleanup')
            """)).isEqualTo(2);
    }

    private UUID insertFestival() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES (:id, 'Goods media schema test', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", id));
        return id;
    }

    private UUID insertGoods(UUID festivalId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", id, "festivalId", festivalId));
        return id;
    }

    private UUID insertMedia(
        UUID festivalId,
        int sourceWidth,
        int sourceHeight,
        int masterWidth,
        OffsetDateTime attachedAt,
        OffsetDateTime detachedAt
    ) {
        UUID id = UUID.randomUUID();
        return insertMedia(
            id,
            festivalId,
            sourceWidth,
            sourceHeight,
            masterWidth,
            OffsetDateTime.now(ZoneOffset.UTC),
            attachedAt,
            detachedAt
        );
    }

    private UUID insertMedia(
        UUID id,
        UUID festivalId,
        int sourceWidth,
        int sourceHeight,
        int masterWidth,
        OffsetDateTime createdAt,
        OffsetDateTime attachedAt,
        OffsetDateTime detachedAt
    ) {
        jdbc.update(mediaInsertSql(), mediaParameters(
            id, festivalId, sourceWidth, sourceHeight, masterWidth, createdAt, attachedAt, detachedAt
        ));
        return id;
    }

    private String mediaInsertSql() {
        return """
            INSERT INTO media_assets (
                id, festival_id, purpose, storage_key, source_sha256, source_size_bytes,
                source_width, source_height, master_width, master_height,
                normalized_format, content_type, created_at, attached_at, detached_at
            ) VALUES (
                :id, :festivalId, :purpose, :storageKey, :sourceSha256, :sourceSizeBytes,
                :sourceWidth, :sourceHeight, :masterWidth, :masterWidth,
                :normalizedFormat, :contentType, :createdAt, :attachedAt, :detachedAt
            )
            """;
    }

    private MapSqlParameterSource mediaParameters(
        UUID id,
        UUID festivalId,
        int sourceWidth,
        int sourceHeight,
        int masterWidth,
        OffsetDateTime createdAt,
        OffsetDateTime attachedAt,
        OffsetDateTime detachedAt
    ) {
        String shard = id.toString().replace("-", "").substring(0, 2);
        return new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("festivalId", festivalId)
            .addValue("purpose", "GOODS_IMAGE")
            .addValue("storageKey", "goods/" + festivalId + "/" + shard + "/" + id)
            .addValue("sourceSha256", SHA256)
            .addValue("sourceSizeBytes", 1L)
            .addValue("sourceWidth", sourceWidth)
            .addValue("sourceHeight", sourceHeight)
            .addValue("masterWidth", masterWidth)
            .addValue("normalizedFormat", "WEBP")
            .addValue("contentType", "image/webp")
            .addValue("createdAt", createdAt)
            .addValue("attachedAt", attachedAt)
            .addValue("detachedAt", detachedAt);
    }

    private void insertAssociation(UUID mediaId, UUID festivalId, UUID goodsId, int sortOrder) {
        jdbc.update("""
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES (:mediaId, :festivalId, :goodsId, :sortOrder)
            """, Map.of(
            "mediaId", mediaId,
            "festivalId", festivalId,
            "goodsId", goodsId,
            "sortOrder", sortOrder
        ));
    }

    private void insertTranslation(UUID mediaId, String locale, String altText) {
        jdbc.update("""
            INSERT INTO goods_image_translations (media_id, locale, alt_text)
            VALUES (:mediaId, :locale, :altText)
            """, Map.of("mediaId", mediaId, "locale", locale, "altText", altText));
    }

    private void assertViolation(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long scalar(String sql) {
        return scalar(sql, Map.of());
    }

    private long scalar(String sql, Map<String, ?> parameters) {
        Long result = jdbc.queryForObject(sql, parameters, Long.class);
        return result == null ? 0 : result;
    }
}

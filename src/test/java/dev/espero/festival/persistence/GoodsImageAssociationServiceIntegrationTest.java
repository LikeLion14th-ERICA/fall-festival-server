package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.domain.Goods;
import dev.espero.festival.media.GoodsImageAssociationService;
import dev.espero.festival.media.UnavailableGoodsImageException;
import dev.espero.festival.web.GoodsInput;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class GoodsImageAssociationServiceIntegrationTest {

    private static final UUID FESTIVAL_A = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID FESTIVAL_B = UUID.fromString("00000000-0000-4000-8000-000000000099");
    private static final Instant ATTACHED_AT = Instant.parse("2030-10-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private GoodsImageAssociationService service;

    @Autowired
    private GoodsStore goodsStore;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        jdbc.update("""
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES (:id, 'Other test festival', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO NOTHING
            """, Map.of("id", FESTIVAL_B));
    }

    @Test
    void attachesInInputOrderWritesOnlyPresentTranslationsAndFeedsTheD1ReadModel() {
        UUID goodsId = insertGoods(FESTIVAL_A);
        UUID lower = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID higher = UUID.fromString("20000000-0000-4000-8000-000000000002");
        insertMedia(FESTIVAL_A, lower, false, false);
        insertMedia(FESTIVAL_A, higher, false, false);

        transactions.executeWithoutResult(status -> service.attachNew(
            FESTIVAL_A,
            goodsId,
            List.of(image(higher, null, null), image(lower, "简体替代文本", "日本語代替テキスト")),
            ATTACHED_AT
        ));

        assertThat(jdbc.query("""
            SELECT media_id, sort_order
            FROM goods_images
            WHERE goods_id = :goodsId
            ORDER BY sort_order
            """, Map.of("goodsId", goodsId),
            (resultSet, rowNumber) -> resultSet.getObject("media_id", UUID.class) + "/" + resultSet.getInt("sort_order")))
            .containsExactly(higher + "/0", lower + "/1");
        assertThat(translationLocales(higher)).containsExactlyInAnyOrder("ko", "en");
        assertThat(translationLocales(lower)).containsExactlyInAnyOrder("ko", "en", "zh-Hans", "ja");
        assertThat(attachedAt(higher)).isEqualTo(ATTACHED_AT);
        assertThat(detachedAt(higher)).isNull();

        Goods goods = goodsStore.findById(FESTIVAL_A, goodsId).orElseThrow();
        assertThat(goods.images()).extracting(image -> image.mediaId()).containsExactly(higher, lower);
        assertThat(goods.images().get(1).translations()).containsOnlyKeys("ko", "en", "zh-Hans", "ja");
    }

    @Test
    void rejectsWrongFestivalMediaAndGoodsWithoutChangingLifecycle() {
        UUID goodsA = insertGoods(FESTIVAL_A);
        UUID goodsB = insertGoods(FESTIVAL_B);
        UUID mediaA = insertMedia(FESTIVAL_A, UUID.randomUUID(), false, false);
        UUID mediaB = insertMedia(FESTIVAL_B, UUID.randomUUID(), false, false);

        assertUnavailable(() -> attach(FESTIVAL_A, goodsA, List.of(image(mediaB, null, null))));
        assertUnavailable(() -> attach(FESTIVAL_A, goodsB, List.of(image(mediaA, null, null))));

        assertThat(associationCount(mediaA, mediaB)).isZero();
        assertThat(attachedAt(mediaA)).isNull();
        assertThat(attachedAt(mediaB)).isNull();
    }

    @Test
    void alreadyAttachedMediaCannotMoveToAnotherGoodsAndTimestampIsNotOverwritten() {
        UUID firstGoods = insertGoods(FESTIVAL_A);
        UUID secondGoods = insertGoods(FESTIVAL_A);
        UUID mediaId = insertMedia(FESTIVAL_A, UUID.randomUUID(), false, false);
        attach(FESTIVAL_A, firstGoods, List.of(image(mediaId, null, null)));

        assertUnavailable(() -> attach(FESTIVAL_A, secondGoods, List.of(image(mediaId, null, null))));

        assertThat(associationGoods(mediaId)).isEqualTo(firstGoods);
        assertThat(attachedAt(mediaId)).isEqualTo(ATTACHED_AT);
    }

    @Test
    void detachedMediaIsTerminal() {
        UUID goodsId = insertGoods(FESTIVAL_A);
        UUID mediaId = insertMedia(FESTIVAL_A, UUID.randomUUID(), true, true);

        assertUnavailable(() -> attach(FESTIVAL_A, goodsId, List.of(image(mediaId, null, null))));

        assertThat(associationCount(mediaId)).isZero();
        assertThat(detachedAt(mediaId)).isNotNull();
    }

    @Test
    void invalidSecondReferenceRollsBackTheWholeTwoImageRequest() {
        UUID goodsId = insertGoods(FESTIVAL_A);
        UUID valid = insertMedia(FESTIVAL_A, UUID.randomUUID(), false, false);
        UUID unknown = UUID.randomUUID();

        assertUnavailable(() -> attach(
            FESTIVAL_A,
            goodsId,
            List.of(image(valid, null, null), image(unknown, null, null))
        ));

        assertThat(associationCount(valid)).isZero();
        assertThat(translationCount(valid)).isZero();
        assertThat(attachedAt(valid)).isNull();
    }

    @Test
    void requiresAnExistingProductTransaction() {
        assertThatThrownBy(() -> service.attachNew(
            FESTIVAL_A,
            UUID.randomUUID(),
            List.of(image(UUID.randomUUID(), null, null)),
            ATTACHED_AT
        )).isInstanceOf(IllegalTransactionStateException.class);
    }

    private void attach(UUID festivalId, UUID goodsId, List<GoodsInput.ImageInput> images) {
        transactions.executeWithoutResult(status -> service.attachNew(festivalId, goodsId, images, ATTACHED_AT));
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(UnavailableGoodsImageException.class)
            .hasMessage("사용할 수 없는 상품 이미지가 포함되어 있습니다.");
    }

    private UUID insertGoods(UUID festivalId) {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", festivalId));
        jdbc.update("""
            INSERT INTO goods_translations (goods_id, locale, name)
            VALUES (:id, 'ko', '상품'), (:id, 'en', 'Goods')
            """, Map.of("id", goodsId));
        return goodsId;
    }

    private UUID insertMedia(UUID festivalId, UUID mediaId, boolean attached, boolean detached) {
        String storageKey = "goods/" + festivalId + "/" + mediaId.toString().substring(0, 2) + "/" + mediaId;
        jdbc.update("""
            INSERT INTO media_assets (
                id, festival_id, purpose, storage_key, source_sha256, source_size_bytes,
                source_width, source_height, master_width, master_height,
                normalized_format, content_type, created_at, attached_at, detached_at
            ) VALUES (
                :id, :festivalId, 'GOODS_IMAGE', :storageKey, :sha, 100,
                1024, 1024, 1024, 1024, 'WEBP', 'image/webp',
                :createdAt,
                CASE WHEN :attached THEN :attachedAt ELSE NULL END,
                CASE WHEN :detached THEN :detachedAt ELSE NULL END
            )
            """, new MapSqlParameterSource()
            .addValue("id", mediaId)
            .addValue("festivalId", festivalId)
            .addValue("storageKey", storageKey)
            .addValue("sha", "0".repeat(64))
            .addValue("createdAt", OffsetDateTime.ofInstant(ATTACHED_AT.minusSeconds(60), ZoneOffset.UTC))
            .addValue("attached", attached)
            .addValue("attachedAt", OffsetDateTime.ofInstant(ATTACHED_AT, ZoneOffset.UTC))
            .addValue("detached", detached)
            .addValue("detachedAt", OffsetDateTime.ofInstant(ATTACHED_AT.plusSeconds(60), ZoneOffset.UTC)));
        return mediaId;
    }

    private static GoodsInput.ImageInput image(UUID mediaId, String zhHans, String ja) {
        Map<String, String> alt = new LinkedHashMap<>();
        alt.put("ko", "한국어 대체 텍스트");
        alt.put("en", "English alt");
        alt.put("zh-Hans", zhHans);
        alt.put("ja", ja);
        return new GoodsInput.ImageInput(mediaId, alt);
    }

    private List<String> translationLocales(UUID mediaId) {
        return jdbc.query(
            "SELECT locale FROM goods_image_translations WHERE media_id = :mediaId",
            Map.of("mediaId", mediaId),
            (resultSet, rowNumber) -> resultSet.getString("locale")
        );
    }

    private long associationCount(UUID... mediaIds) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM goods_images WHERE media_id IN (:mediaIds)",
            new MapSqlParameterSource("mediaIds", List.of(mediaIds)),
            Long.class
        );
        return count == null ? 0 : count;
    }

    private long translationCount(UUID mediaId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM goods_image_translations WHERE media_id = :mediaId",
            Map.of("mediaId", mediaId),
            Long.class
        );
        return count == null ? 0 : count;
    }

    private UUID associationGoods(UUID mediaId) {
        return jdbc.queryForObject(
            "SELECT goods_id FROM goods_images WHERE media_id = :mediaId",
            Map.of("mediaId", mediaId),
            UUID.class
        );
    }

    private Instant attachedAt(UUID mediaId) {
        OffsetDateTime value = jdbc.queryForObject(
            "SELECT attached_at FROM media_assets WHERE id = :mediaId",
            Map.of("mediaId", mediaId),
            OffsetDateTime.class
        );
        return value == null ? null : value.toInstant();
    }

    private Instant detachedAt(UUID mediaId) {
        OffsetDateTime value = jdbc.queryForObject(
            "SELECT detached_at FROM media_assets WHERE id = :mediaId",
            Map.of("mediaId", mediaId),
            OffsetDateTime.class
        );
        return value == null ? null : value.toInstant();
    }
}

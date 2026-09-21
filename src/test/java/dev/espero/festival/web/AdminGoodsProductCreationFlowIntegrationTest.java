package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.auth.AdminPrincipal;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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

/** Exercises complete administrator product creation against PostgreSQL. */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminGoodsProductCreationFlowIntegrationTest {

    private static final String ROUTE = "/api/v2/admin/products";
    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID OTHER_FESTIVAL_ID = UUID.fromString("4b028799-51c2-43fb-943c-f55ec5c60d99");
    private static final UUID ADMIN_ID = UUID.fromString("a4d71f22-4d36-42eb-9bde-1829936215c2");
    private static final UUID OPENAPI_MEDIA_ID = UUID.fromString("00000000-0000-4000-8000-000000000050");
    private static final Path MEDIA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "festival-product-create-flow-" + UUID.randomUUID()
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
    private MediaStorage mediaStorage;

    @Autowired
    private MutableClock clock;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clock.set("2030-10-01T12:00:00+09:00");
        deleteBusinessRows();
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
        deleteTree(MEDIA_ROOT);
        Files.createDirectories(MEDIA_ROOT.resolve(".staging"));
    }

    @AfterAll
    static void removeMediaRoot() throws IOException {
        deleteTree(MEDIA_ROOT);
    }

    @Test
    void createsOpenApiShapedOptionsProductAndExposesEveryReadModel() throws Exception {
        insertUnattachedMediaWithFiles(FESTIVAL_ID, OPENAPI_MEDIA_ID);
        String body = openApiProductExample().toString();

        MvcResult result = postProduct(body, "openapi-options-product")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.optionMode").value("OPTIONS"))
            .andExpect(jsonPath("$.data.colors", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.data.sizes", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.data.combinations", org.hamcrest.Matchers.hasSize(3)))
            .andExpect(jsonPath("$.data.combinations[*].status", org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.is("ON_SALE"))))
            .andExpect(jsonPath("$.data.images[0].mediaId").value(OPENAPI_MEDIA_ID.toString()))
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.locale").value("ko"))
            .andReturn();
        UUID goodsId = createdGoodsId(result);

        assertThat(count("goods", "id", goodsId)).isOne();
        assertThat(count("goods_translations", "goods_id", goodsId)).isEqualTo(2);
        assertThat(orderedIds("goods_colors", goodsId)).containsExactly(
            UUID.fromString("00000000-0000-4000-8000-000000000101"),
            UUID.fromString("00000000-0000-4000-8000-000000000102")
        );
        assertThat(orderedIds("goods_sizes", goodsId)).containsExactly(
            UUID.fromString("00000000-0000-4000-8000-000000000201"),
            UUID.fromString("00000000-0000-4000-8000-000000000202")
        );
        assertThat(count("goods_combinations", "goods_id", goodsId)).isEqualTo(3);
        assertThat(jdbc.queryForList(
            "SELECT availability FROM goods_combinations WHERE goods_id = :id",
            Map.of("id", goodsId), String.class
        )).containsOnly("ON_SALE");
        assertThat(count("goods_images", "goods_id", goodsId)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT attached_at IS NOT NULL FROM media_assets WHERE id = :id",
            Map.of("id", OPENAPI_MEDIA_ID), Boolean.class
        )).isTrue();
        assertProductAudit(goodsId);

        mvc.perform(get("/api/v2/goods"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(goodsId.toString()));
        mvc.perform(get("/api/v2/goods/" + goodsId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.images[0].masterUrl")
                .value("/api/v2/media/goods-images/" + OPENAPI_MEDIA_ID + "/master"));
        mvc.perform(get("/api/v2/goods/" + goodsId + "/availability"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.combinations", org.hamcrest.Matchers.hasSize(3)))
            .andExpect(jsonPath("$.data.combinations[*].status", org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.is("ON_SALE"))));
        mvc.perform(asAdmin(get(ROUTE + "/" + goodsId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(goodsId.toString()));
        mvc.perform(asAdmin(get(ROUTE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(goodsId.toString()));
        performStreaming("/api/v2/media/goods-images/" + OPENAPI_MEDIA_ID + "/master")
            .andExpect(status().isOk())
            .andExpect(content().bytes(mediaBytes(MediaVariant.MASTER)));
    }

    @Test
    void createsSingleProductWithExactlyOneOpaqueCombination() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);

        MvcResult result = postProduct(singleProduct(mediaId).toString(), "single-product")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.optionMode").value("SINGLE"))
            .andExpect(jsonPath("$.data.colors", org.hamcrest.Matchers.empty()))
            .andExpect(jsonPath("$.data.sizes", org.hamcrest.Matchers.empty()))
            .andExpect(jsonPath("$.data.combinations", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.combinations[0].colorId", org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.combinations[0].sizeId", org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.combinations[0].status").value("ON_SALE"))
            .andReturn();
        UUID goodsId = createdGoodsId(result);

        Map<String, Object> combination = jdbc.queryForMap("""
            SELECT color_id, size_id, availability
            FROM goods_combinations WHERE goods_id = :id
            """, Map.of("id", goodsId));
        assertThat(combination.get("color_id")).isNull();
        assertThat(combination.get("size_id")).isNull();
        assertThat(combination.get("availability")).isEqualTo("ON_SALE");
        assertThat(count("goods_colors", "goods_id", goodsId)).isZero();
        assertThat(count("goods_sizes", "goods_id", goodsId)).isZero();
    }

    @Test
    void rollsBackEveryProductRowWhenAnyMediaIsUnknown() throws Exception {
        UUID validMedia = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, validMedia);
        JsonNode body = singleProduct(validMedia);
        addSecondImage(body, UUID.randomUUID());

        postProduct(body.toString(), "unknown-media")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("INVALID_MEDIA_REFERENCE"))
            .andExpect(jsonPath("$.error.message").value("사용할 수 없는 상품 이미지가 포함되어 있습니다."))
            .andExpect(jsonPath("$.error.retryable").value(false));

        assertNoCreatedProductState();
        assertMediaUnattached(validMedia);
    }

    @Test
    void rejectsCrossFestivalMediaWithoutLeakingItsLifecycle() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(OTHER_FESTIVAL_ID, mediaId);

        postProduct(singleProduct(mediaId).toString(), "cross-festival-media")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("INVALID_MEDIA_REFERENCE"));

        assertNoCreatedProductState();
        assertMediaUnattached(mediaId);
    }

    @Test
    void rejectsAlreadyAttachedMediaAndPreservesTheOriginalAssociation() throws Exception {
        UUID originalGoodsId = insertExistingSingleGoods();
        UUID mediaId = UUID.randomUUID();
        insertAttachedMedia(FESTIVAL_ID, mediaId, originalGoodsId);

        postProduct(singleProduct(mediaId).toString(), "already-attached-media")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("INVALID_MEDIA_REFERENCE"));

        assertThat(countRows("goods")).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT goods_id FROM goods_images WHERE media_id = :id", Map.of("id", mediaId), UUID.class
        )).isEqualTo(originalGoodsId);
        assertThat(jdbc.queryForObject(
            "SELECT attached_at IS NOT NULL FROM media_assets WHERE id = :id",
            Map.of("id", mediaId), Boolean.class
        )).isTrue();
        assertThat(productAuditCount()).isZero();
    }

    @Test
    void replaysOriginalBusinessResultWithFreshMetaAndRejectsChangedPayload() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        JsonNode body = singleProduct(mediaId);

        MvcResult first = postProduct(body.toString(), "product-replay-key", "request-A")
            .andExpect(status().isCreated())
            .andReturn();
        clock.advance(Duration.ofSeconds(2));
        MvcResult replay = postProduct(body.toString(), "product-replay-key", "request-B")
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode firstJson = response(first);
        JsonNode replayJson = response(replay);

        assertThat(replayJson.path("data")).isEqualTo(firstJson.path("data"));
        assertThat(replayJson.path("meta").path("requestId").asString())
            .isNotEqualTo(firstJson.path("meta").path("requestId").asString());
        assertThat(replayJson.path("meta").path("serverTime").asString())
            .isNotEqualTo(firstJson.path("meta").path("serverTime").asString());
        assertThat(countRows("goods")).isOne();
        assertThat(countRows("goods_images")).isOne();
        assertThat(productAuditCount()).isOne();

        ((tools.jackson.databind.node.ObjectNode) body.path("price")).put("amount", 2000);
        postProduct(body.toString(), "product-replay-key")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(countRows("goods")).isOne();
        assertThat(productAuditCount()).isOne();
    }

    @Test
    void replaysStoredProductResponsesWithFieldsFromAnAdjacentDeployment() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        JsonNode body = singleProduct(mediaId);
        MvcResult first = postProduct(body.toString(), "product-response-compat")
            .andExpect(status().isCreated())
            .andReturn();
        tools.jackson.databind.node.ObjectNode stored =
            (tools.jackson.databind.node.ObjectNode) response(first);
        tools.jackson.databind.node.ObjectNode storedData =
            (tools.jackson.databind.node.ObjectNode) stored.path("data");
        storedData.put("newerField", true);
        ((tools.jackson.databind.node.ObjectNode) storedData.path("price")).put("newerPriceField", true);
        assertThat(jdbc.update(
            "UPDATE admin_idempotency_records SET response_body = :body",
            Map.of("body", objectMapper.writeValueAsString(stored))
        )).isOne();

        MvcResult replay = postProduct(body.toString(), "product-response-compat")
            .andExpect(status().isCreated())
            .andReturn();

        assertThat(response(replay).path("data").path("id").asString())
            .isEqualTo(response(first).path("data").path("id").asString());
        assertThat(productAuditCount()).isOne();
    }

    @Test
    void rejectsMissingOrInvalidIdempotencyKeysWithoutProductSideEffects() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        String body = singleProduct(mediaId).toString();

        mvc.perform(asAdmin(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(body)))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        postProduct(body, "invalid key")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertNoCreatedProductState();
    }

    @Test
    void enforcesAuthenticationNoQueryAndJsonSyntaxAndMediaType() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        String body = singleProduct(mediaId).toString();

        mvc.perform(post(ROUTE)
                .header("Idempotency-Key", "anonymous-product")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(asAdmin(post(ROUTE)
                .queryParam("foo", "bar")
                .header("Idempotency-Key", "query-product")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(asAdmin(post(ROUTE)
                .header("Idempotency-Key", "wrong-type-product")
                .contentType(MediaType.TEXT_PLAIN)
                .content(body)))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.error.message").value("application/json 요청이 필요합니다."));
        mvc.perform(asAdmin(post(ROUTE)
                .header("Idempotency-Key", "malformed-product")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{")))
            .andExpect(status().isBadRequest());

        assertNoCreatedProductState();
    }

    @Test
    void rejectsUnknownNestedJsonFieldsWithoutProductSideEffects() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        JsonNode body = singleProduct(mediaId);
        ((tools.jackson.databind.node.ObjectNode) body.path("price")).put("unexpected", true);

        postProduct(body.toString(), "unexpected-product-field")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertNoCreatedProductState();
    }

    @Test
    void updatesOptionsDifferentiallyAndReordersStableColorsSizesAndImages() throws Exception {
        UUID firstMedia = UUID.randomUUID();
        UUID secondMedia = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, firstMedia);
        insertUnattachedMediaWithFiles(FESTIVAL_ID, secondMedia);
        UUID colorA = UUID.randomUUID();
        UUID colorB = UUID.randomUUID();
        UUID sizeA = UUID.randomUUID();
        UUID sizeB = UUID.randomUUID();
        JsonNode create = optionsProduct(
            List.of(firstMedia, secondMedia), List.of(colorA, colorB), List.of(sizeA, sizeB),
            List.of(pair(colorA, sizeA), pair(colorA, sizeB), pair(colorB, sizeB))
        );
        MvcResult created = postProduct(create.toString(), "options-update-create").andExpect(status().isCreated()).andReturn();
        UUID goodsId = createdGoodsId(created);
        Map<String, Map<String, Object>> before = combinationRows(goodsId);
        Map<String, Object> soldOut = before.get(pairKey(colorA, sizeA));
        UUID soldOutId = (UUID) soldOut.get("id");
        mvc.perform(asAdmin(put("/api/v2/admin/goods/" + goodsId + "/combinations/" + soldOutId + "/availability"))
                .header("Idempotency-Key", "make-sold-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SOLD_OUT\"}"))
            .andExpect(status().isOk());
        before = combinationRows(goodsId);
        soldOut = before.get(pairKey(colorA, sizeA));
        Object soldOutUpdatedAt = soldOut.get("updated_at");
        Map<UUID, Object> attachedAt = lifecycleTimes(firstMedia, secondMedia);
        String etag = adminEtag(goodsId);
        clock.advance(Duration.ofMinutes(5));

        JsonNode update = optionsProduct(
            List.of(secondMedia, firstMedia), List.of(colorB, colorA), List.of(sizeB, sizeA),
            List.of(pair(colorA, sizeA), pair(colorB, sizeB), pair(colorB, sizeA))
        );
        ((tools.jackson.databind.node.ObjectNode) update.path("translations").path("ko"))
            .put("name", "수정된 옵션 상품");
        MvcResult response = putProduct(goodsId, update.toString(), "options-update", etag, "options-request")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.price.amount").value(2000))
            .andExpect(jsonPath("$.data.images[0].mediaId").value(secondMedia.toString()))
            .andExpect(jsonPath("$.data.images[1].mediaId").value(firstMedia.toString()))
            .andReturn();

        assertThat(orderedIds("goods_colors", goodsId)).containsExactly(colorB, colorA);
        assertThat(orderedIds("goods_sizes", goodsId)).containsExactly(sizeB, sizeA);
        Map<String, Map<String, Object>> after = combinationRows(goodsId);
        assertThat(after).hasSize(3);
        assertThat(after.get(pairKey(colorA, sizeA)).get("id")).isEqualTo(soldOutId);
        assertThat(after.get(pairKey(colorA, sizeA)).get("availability")).isEqualTo("SOLD_OUT");
        assertThat(after.get(pairKey(colorA, sizeA)).get("updated_at")).isEqualTo(soldOutUpdatedAt);
        assertThat(after.get(pairKey(colorB, sizeB)).get("id"))
            .isEqualTo(before.get(pairKey(colorB, sizeB)).get("id"));
        assertThat(after).doesNotContainKey(pairKey(colorA, sizeB));
        assertThat(after.get(pairKey(colorB, sizeA)).get("availability")).isEqualTo("ON_SALE");
        assertThat(lifecycleTimes(firstMedia, secondMedia)).isEqualTo(attachedAt);
        assertThat(response(response).path("data").path("translations").path("ko").path("name").asString())
            .isEqualTo("수정된 옵션 상품");
        assertThat(auditCount("PRODUCT_UPDATED", goodsId)).isOne();
    }

    @Test
    void replacesImagesAndRollsBackEveryChangeWhenANewImageIsInvalid() throws Exception {
        UUID first = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, first);
        insertUnattachedMediaWithFiles(FESTIVAL_ID, kept);
        insertUnattachedMediaWithFiles(FESTIVAL_ID, added);
        JsonNode create = singleProduct(first);
        addSecondImage(create, kept);
        UUID goodsId = createdGoodsId(postProduct(create.toString(), "image-replace-create")
            .andExpect(status().isCreated()).andReturn());
        Object keptAttachedAt = lifecycle(kept).get("attached_at");
        String etag = adminEtag(goodsId);
        JsonNode replacement = singleProduct(kept);
        addSecondImage(replacement, added);
        ((tools.jackson.databind.node.ObjectNode) replacement.path("images").get(0).path("alt"))
            .put("ko", "유지 이미지 수정");

        putProduct(goodsId, replacement.toString(), "image-replace", etag, null)
            .andExpect(status().isOk());

        assertThat(lifecycle(kept).get("attached_at")).isEqualTo(keptAttachedAt);
        assertThat(lifecycle(kept).get("detached_at")).isNull();
        assertThat(lifecycle(added).get("attached_at")).isNotNull();
        assertThat(lifecycle(first).get("detached_at")).isNotNull();
        assertThat(Files.exists(mediaFile(FESTIVAL_ID, first, MediaVariant.MASTER))).isTrue();
        mvc.perform(get("/api/v2/media/goods-images/" + first + "/master")).andExpect(status().isNotFound());
        performStreaming("/api/v2/media/goods-images/" + kept + "/master").andExpect(status().isOk());
        performStreaming("/api/v2/media/goods-images/" + added + "/master").andExpect(status().isOk());

        String freshEtag = adminEtag(goodsId);
        JsonNode invalid = singleProduct(kept);
        addSecondImage(invalid, UUID.randomUUID());
        Map<String, Object> goodsBefore = jdbc.queryForMap(
            "SELECT price_amount, updated_at FROM goods WHERE id = :id", Map.of("id", goodsId)
        );
        Map<UUID, Map<String, Object>> lifecycleBefore = Map.of(
            kept, lifecycle(kept), added, lifecycle(added)
        );
        putProduct(goodsId, invalid.toString(), "invalid-replacement", freshEtag, null)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("INVALID_MEDIA_REFERENCE"));

        assertThat(jdbc.queryForMap(
            "SELECT price_amount, updated_at FROM goods WHERE id = :id", Map.of("id", goodsId)
        )).isEqualTo(goodsBefore);
        assertThat(lifecycle(kept)).isEqualTo(lifecycleBefore.get(kept));
        assertThat(lifecycle(added)).isEqualTo(lifecycleBefore.get(added));
        assertThat(associatedMedia(goodsId)).containsExactly(kept, added);
        assertThat(auditCount("PRODUCT_UPDATED", goodsId)).isOne();
    }

    @Test
    void preservesSingleSaleStateButResetsAllCombinationsAcrossModeSwitches() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        UUID goodsId = createdGoodsId(postProduct(singleProduct(mediaId).toString(), "mode-create")
            .andExpect(status().isCreated()).andReturn());
        Map<String, Object> original = jdbc.queryForMap(
            "SELECT id, updated_at FROM goods_combinations WHERE goods_id = :id", Map.of("id", goodsId)
        );
        jdbc.update("UPDATE goods_combinations SET availability = 'SOLD_OUT' WHERE goods_id = :id", Map.of("id", goodsId));
        String etag = adminEtag(goodsId);
        clock.advance(Duration.ofMinutes(1));

        putProduct(goodsId, singleProduct(mediaId).toString(), "single-preserve", etag, null)
            .andExpect(status().isOk());
        Map<String, Object> preserved = jdbc.queryForMap(
            "SELECT id, availability, updated_at FROM goods_combinations WHERE goods_id = :id", Map.of("id", goodsId)
        );
        assertThat(preserved.get("id")).isEqualTo(original.get("id"));
        assertThat(preserved.get("availability")).isEqualTo("SOLD_OUT");
        assertThat(preserved.get("updated_at")).isEqualTo(original.get("updated_at"));

        UUID color = UUID.randomUUID();
        UUID size = UUID.randomUUID();
        JsonNode options = optionsProduct(
            List.of(mediaId), List.of(color), List.of(size), List.of(pair(color, size))
        );
        UUID oldSingleId = (UUID) preserved.get("id");
        putProduct(goodsId, options.toString(), "to-options", adminEtag(goodsId), null)
            .andExpect(status().isOk());
        Map<String, Object> option = jdbc.queryForMap(
            "SELECT id, availability FROM goods_combinations WHERE goods_id = :id", Map.of("id", goodsId)
        );
        assertThat(option.get("id")).isNotEqualTo(oldSingleId);
        assertThat(option.get("availability")).isEqualTo("ON_SALE");

        UUID oldOptionId = (UUID) option.get("id");
        putProduct(goodsId, singleProduct(mediaId).toString(), "to-single", adminEtag(goodsId), null)
            .andExpect(status().isOk());
        Map<String, Object> finalSingle = jdbc.queryForMap(
            "SELECT id, color_id, size_id, availability FROM goods_combinations WHERE goods_id = :id",
            Map.of("id", goodsId)
        );
        assertThat(finalSingle.get("id")).isNotEqualTo(oldOptionId);
        assertThat(finalSingle.get("color_id")).isNull();
        assertThat(finalSingle.get("size_id")).isNull();
        assertThat(finalSingle.get("availability")).isEqualTo("ON_SALE");
    }

    @Test
    void enforcesPutEtagsAndReplaysOriginalBusinessResultWithFreshMeta() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        UUID goodsId = createdGoodsId(postProduct(singleProduct(mediaId).toString(), "put-replay-create")
            .andExpect(status().isCreated()).andReturn());
        String current = adminEtag(goodsId);
        String body = singleProduct(mediaId).toString();

        mvc.perform(asAdmin(put(ROUTE + "/" + goodsId))
                .header("Idempotency-Key", "missing-etag")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        putProduct(goodsId, body, "invalid-etag", "W/" + current, null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_IF_MATCH"));
        putProduct(goodsId, body, "stale-etag", "\"" + "0".repeat(64) + "\"", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EDIT_CONFLICT"));

        clock.advance(Duration.ofSeconds(1));
        MvcResult first = putProduct(goodsId, body, "put-replay", current, "put-request-A")
            .andExpect(status().isOk()).andReturn();
        clock.advance(Duration.ofSeconds(2));
        MvcResult replay = putProduct(goodsId, body, "put-replay", current, "put-request-B")
            .andExpect(status().isOk()).andReturn();
        assertThat(response(replay).path("data")).isEqualTo(response(first).path("data"));
        assertThat(response(replay).path("meta").path("requestId").asString())
            .isNotEqualTo(response(first).path("meta").path("requestId").asString());
        assertThat(auditCount("PRODUCT_UPDATED", goodsId)).isOne();

        String fresh = adminEtag(goodsId);
        putProduct(goodsId, body, "put-replay", fresh, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        JsonNode changed = singleProduct(mediaId);
        ((tools.jackson.databind.node.ObjectNode) changed.path("price")).put("amount", 9999);
        putProduct(goodsId, changed.toString(), "put-replay", current, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsForeignColorIdentifiersWithoutChangingTheTargetProduct() throws Exception {
        UUID mediaA = UUID.randomUUID();
        UUID mediaB = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaA);
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaB);
        UUID foreignColor = UUID.randomUUID();
        UUID foreignSize = UUID.randomUUID();
        JsonNode owner = optionsProduct(
            List.of(mediaA), List.of(foreignColor), List.of(foreignSize),
            List.of(pair(foreignColor, foreignSize))
        );
        postProduct(owner.toString(), "foreign-owner").andExpect(status().isCreated());
        UUID targetId = createdGoodsId(postProduct(singleProduct(mediaB).toString(), "foreign-target")
            .andExpect(status().isCreated()).andReturn());
        UUID ownSize = UUID.randomUUID();
        JsonNode collision = optionsProduct(
            List.of(mediaB), List.of(foreignColor), List.of(ownSize), List.of(pair(foreignColor, ownSize))
        );

        putProduct(targetId, collision.toString(), "foreign-collision", adminEtag(targetId), null)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.queryForObject(
            "SELECT option_mode FROM goods WHERE id = :id", Map.of("id", targetId), String.class
        )).isEqualTo("SINGLE");
        assertThat(auditCount("PRODUCT_UPDATED", targetId)).isZero();
    }

    @Test
    void hardDeletesProductDetachesMediaAndReplaysAfterTheRowIsGone() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, first);
        insertUnattachedMediaWithFiles(FESTIVAL_ID, second);
        UUID color = UUID.randomUUID();
        UUID size = UUID.randomUUID();
        JsonNode product = optionsProduct(
            List.of(first, second), List.of(color), List.of(size), List.of(pair(color, size))
        );
        UUID goodsId = createdGoodsId(postProduct(product.toString(), "delete-create")
            .andExpect(status().isCreated()).andReturn());
        String etag = adminEtag(goodsId);

        MvcResult deleted = deleteProduct(goodsId, "delete-replay", etag, "delete-request-A")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(goodsId.toString()))
            .andExpect(jsonPath("$.data.deleted").value(true))
            .andReturn();
        clock.advance(Duration.ofSeconds(2));
        MvcResult replay = deleteProduct(goodsId, "delete-replay", etag, "delete-request-B")
            .andExpect(status().isOk()).andReturn();
        assertThat(response(replay).path("data")).isEqualTo(response(deleted).path("data"));
        assertThat(response(replay).path("meta").path("requestId").asString())
            .isNotEqualTo(response(deleted).path("meta").path("requestId").asString());
        deleteProduct(goodsId, "delete-replay", "\"" + "f".repeat(64) + "\"", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        deleteProduct(goodsId, "delete-new-key", etag, null)
            .andExpect(status().isNotFound());

        for (String table : List.of(
            "goods", "goods_translations", "goods_colors", "goods_color_translations",
            "goods_sizes", "goods_size_translations", "goods_combinations",
            "goods_images", "goods_image_translations"
        )) {
            assertThat(countRows(table)).as(table).isZero();
        }
        assertThat(countRows("media_assets")).isEqualTo(2);
        assertThat(lifecycle(first).get("attached_at")).isNotNull();
        assertThat(lifecycle(first).get("detached_at")).isNotNull();
        assertThat(lifecycle(second).get("detached_at")).isNotNull();
        assertThat(Files.exists(mediaFile(FESTIVAL_ID, first, MediaVariant.MASTER))).isTrue();
        assertThat(auditCount("PRODUCT_DELETED", goodsId)).isOne();
        mvc.perform(get("/api/v2/goods/" + goodsId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/goods/" + goodsId + "/availability")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/goods/" + goodsId + "/payment-guide")).andExpect(status().isNotFound());
        mvc.perform(asAdmin(get(ROUTE + "/" + goodsId))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/media/goods-images/" + first + "/master")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsDeleteWithoutOneCurrentStrongEtagAndLeavesProductIntact() throws Exception {
        UUID mediaId = UUID.randomUUID();
        insertUnattachedMediaWithFiles(FESTIVAL_ID, mediaId);
        UUID goodsId = createdGoodsId(postProduct(singleProduct(mediaId).toString(), "delete-etag-create")
            .andExpect(status().isCreated()).andReturn());

        mvc.perform(asAdmin(delete(ROUTE + "/" + goodsId)).header("Idempotency-Key", "delete-no-etag"))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        deleteProduct(goodsId, "delete-invalid-etag", "bad", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_IF_MATCH"));
        deleteProduct(goodsId, "delete-stale-etag", "\"" + "0".repeat(64) + "\"", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EDIT_CONFLICT"));

        assertThat(count("goods", "id", goodsId)).isOne();
        assertThat(lifecycle(mediaId).get("detached_at")).isNull();
        assertThat(auditCount("PRODUCT_DELETED", goodsId)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions putProduct(
        UUID goodsId,
        String body,
        String key,
        String ifMatch,
        String clientRequestId
    ) throws Exception {
        MockHttpServletRequestBuilder request = asAdmin(put(ROUTE + "/" + goodsId))
            .header("Idempotency-Key", key)
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (clientRequestId != null) {
            request.header("X-Request-Id", clientRequestId);
        }
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions deleteProduct(
        UUID goodsId,
        String key,
        String ifMatch,
        String clientRequestId
    ) throws Exception {
        MockHttpServletRequestBuilder request = asAdmin(delete(ROUTE + "/" + goodsId))
            .header("Idempotency-Key", key)
            .header("If-Match", ifMatch);
        if (clientRequestId != null) {
            request.header("X-Request-Id", clientRequestId);
        }
        return mvc.perform(request);
    }

    private String adminEtag(UUID goodsId) throws Exception {
        return mvc.perform(asAdmin(get(ROUTE + "/" + goodsId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("ETag");
    }

    private JsonNode optionsProduct(
        List<UUID> mediaIds,
        List<UUID> colorIds,
        List<UUID> sizeIds,
        List<OptionPairInput> options
    ) {
        tools.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("optionMode", "OPTIONS");
        tools.jackson.databind.node.ObjectNode translations = root.putObject("translations");
        translations.putObject("ko").put("name", "옵션 상품").put("description", "옵션 설명");
        translations.putObject("en").put("name", "Options product").put("description", "Options description");
        translations.putNull("zh-Hans");
        translations.putNull("ja");
        root.putObject("price").put("amount", 2000).put("currency", "KRW");
        tools.jackson.databind.node.ArrayNode images = root.putArray("images");
        for (int index = 0; index < mediaIds.size(); index++) {
            tools.jackson.databind.node.ObjectNode image = images.addObject();
            image.put("mediaId", mediaIds.get(index).toString());
            tools.jackson.databind.node.ObjectNode alt = image.putObject("alt");
            alt.put("ko", "상품 이미지 " + index);
            alt.put("en", "Product image " + index);
            alt.putNull("zh-Hans");
            alt.putNull("ja");
        }
        tools.jackson.databind.node.ArrayNode colors = root.putArray("colors");
        for (int index = 0; index < colorIds.size(); index++) {
            tools.jackson.databind.node.ObjectNode color = colors.addObject();
            color.put("id", colorIds.get(index).toString());
            tools.jackson.databind.node.ObjectNode values = color.putObject("translations");
            values.putObject("ko").put("name", "색상 " + index);
            values.putObject("en").put("name", "Color " + index);
            values.putNull("zh-Hans");
            values.putNull("ja");
        }
        tools.jackson.databind.node.ArrayNode sizes = root.putArray("sizes");
        for (int index = 0; index < sizeIds.size(); index++) {
            tools.jackson.databind.node.ObjectNode size = sizes.addObject();
            size.put("id", sizeIds.get(index).toString());
            tools.jackson.databind.node.ObjectNode values = size.putObject("translations");
            values.putObject("ko").put("label", "크기 " + index);
            values.putObject("en").put("label", "Size " + index);
            values.putNull("zh-Hans");
            values.putNull("ja");
        }
        tools.jackson.databind.node.ArrayNode optionNodes = root.putArray("options");
        options.forEach(option -> optionNodes.addObject()
            .put("colorId", option.colorId().toString())
            .put("sizeId", option.sizeId().toString()));
        return root;
    }

    private static OptionPairInput pair(UUID colorId, UUID sizeId) {
        return new OptionPairInput(colorId, sizeId);
    }

    private static String pairKey(UUID colorId, UUID sizeId) {
        return colorId + "/" + sizeId;
    }

    private Map<String, Map<String, Object>> combinationRows(UUID goodsId) {
        Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
        jdbc.queryForList("""
            SELECT id, color_id, size_id, availability, updated_at
            FROM goods_combinations WHERE goods_id = :id
            """, Map.of("id", goodsId)).forEach(row -> result.put(
            pairKey((UUID) row.get("color_id"), (UUID) row.get("size_id")), row
        ));
        return result;
    }

    private Map<UUID, Object> lifecycleTimes(UUID... mediaIds) {
        Map<UUID, Object> result = new java.util.LinkedHashMap<>();
        for (UUID mediaId : mediaIds) {
            result.put(mediaId, lifecycle(mediaId).get("attached_at"));
        }
        return result;
    }

    private Map<String, Object> lifecycle(UUID mediaId) {
        return jdbc.queryForMap(
            "SELECT attached_at, detached_at FROM media_assets WHERE id = :id", Map.of("id", mediaId)
        );
    }

    private List<UUID> associatedMedia(UUID goodsId) {
        return jdbc.queryForList(
            "SELECT media_id FROM goods_images WHERE goods_id = :id ORDER BY sort_order",
            Map.of("id", goodsId), UUID.class
        );
    }

    private Path mediaFile(UUID festivalId, UUID mediaId, MediaVariant variant) {
        String filename = switch (variant) {
            case MASTER -> "master.webp";
            case THUMB_320 -> "320.webp";
            case THUMB_640 -> "640.webp";
        };
        return MEDIA_ROOT.resolve("goods").resolve(festivalId.toString())
            .resolve(mediaId.toString().replace("-", "").substring(0, 2))
            .resolve(mediaId.toString()).resolve(filename);
    }

    private long auditCount(String action, UUID goodsId) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM admin_audit_events
            WHERE action = :action AND resource_type = 'GOODS' AND resource_id = :resourceId
            """, Map.of("action", action, "resourceId", goodsId.toString()), Long.class);
        return count == null ? 0 : count;
    }

    private record OptionPairInput(UUID colorId, UUID sizeId) {}

    private org.springframework.test.web.servlet.ResultActions postProduct(String body, String key) throws Exception {
        return postProduct(body, key, null);
    }

    private org.springframework.test.web.servlet.ResultActions postProduct(
        String body,
        String key,
        String clientRequestId
    ) throws Exception {
        MockHttpServletRequestBuilder request = asAdmin(post(ROUTE))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (clientRequestId != null) {
            request.header("X-Request-Id", clientRequestId);
        }
        return mvc.perform(request);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
            new AdminPrincipal(ADMIN_ID, "goods-admin", "ADMIN"),
            null,
            List.of(new SimpleGrantedAuthority("ADMIN"))
        )));
    }

    private org.springframework.test.web.servlet.ResultActions performStreaming(String path) throws Exception {
        MvcResult initial = mvc.perform(get(path)).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(initial));
    }

    private JsonNode openApiProductExample() throws IOException {
        JsonNode document = objectMapper.readTree(Files.readString(Path.of("api-v2", "openapi.json")));
        return document.path("paths").path(ROUTE).path("post")
            .path("requestBody").path("content").path("application/json").path("example");
    }

    private JsonNode singleProduct(UUID mediaId) throws IOException {
        return objectMapper.readTree("""
            {
              "optionMode": "SINGLE",
              "translations": {
                "ko": {"name": "단일 상품", "description": null},
                "en": {"name": "Single product", "description": null},
                "zh-Hans": null,
                "ja": null
              },
              "price": {"amount": 1000, "currency": "KRW"},
              "images": [{
                "mediaId": "%s",
                "alt": {"ko": "상품 앞면", "en": "Product front", "zh-Hans": null, "ja": null}
              }],
              "colors": [],
              "sizes": [],
              "options": []
            }
            """.formatted(mediaId));
    }

    private void addSecondImage(JsonNode body, UUID mediaId) throws IOException {
        JsonNode second = objectMapper.readTree("""
            {
              "mediaId": "%s",
              "alt": {"ko": "상품 뒷면", "en": "Product back", "zh-Hans": null, "ja": null}
            }
            """.formatted(mediaId));
        ((tools.jackson.databind.node.ArrayNode) body.path("images")).add(second);
    }

    private UUID createdGoodsId(MvcResult result) throws Exception {
        return UUID.fromString(response(result).path("data").path("id").asString());
    }

    private JsonNode response(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void insertUnattachedMediaWithFiles(UUID festivalId, UUID mediaId) throws IOException {
        insertMedia(festivalId, mediaId, false);
        UUID operationId = UUID.randomUUID();
        mediaStorage.createStaging(operationId);
        for (MediaVariant variant : MediaVariant.values()) {
            try (var output = mediaStorage.openStagingOutput(operationId, variant)) {
                output.write(mediaBytes(variant));
            }
        }
        mediaStorage.finalizeStaging(operationId, festivalId, mediaId);
    }

    private void insertAttachedMedia(UUID festivalId, UUID mediaId, UUID goodsId) {
        insertMedia(festivalId, mediaId, true);
        jdbc.update("""
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES (:mediaId, :festivalId, :goodsId, 0)
            """, new MapSqlParameterSource()
            .addValue("mediaId", mediaId)
            .addValue("festivalId", festivalId)
            .addValue("goodsId", goodsId));
        jdbc.update("""
            INSERT INTO goods_image_translations (media_id, locale, alt_text)
            VALUES (:mediaId, 'ko', '기존 이미지'), (:mediaId, 'en', 'Existing image')
            """, Map.of("mediaId", mediaId));
    }

    private void insertMedia(UUID festivalId, UUID mediaId, boolean attached) {
        jdbc.update("""
            INSERT INTO media_assets (
                id, festival_id, purpose, storage_key, source_sha256, source_size_bytes,
                source_width, source_height, master_width, master_height,
                normalized_format, content_type, created_at, attached_at, detached_at
            ) VALUES (
                :id, :festivalId, 'GOODS_IMAGE', :storageKey, :sha, 100,
                1024, 1024, 1024, 1024, 'WEBP', 'image/webp', CURRENT_TIMESTAMP,
                CASE WHEN :attached THEN CURRENT_TIMESTAMP ELSE NULL END, NULL
            )
            """, new MapSqlParameterSource()
            .addValue("id", mediaId)
            .addValue("festivalId", festivalId)
            .addValue("storageKey", mediaStorage.storageKey(festivalId, mediaId))
            .addValue("sha", mediaId.toString().replace("-", "") + mediaId.toString().replace("-", ""))
            .addValue("attached", attached));
    }

    private UUID insertExistingSingleGoods() {
        UUID goodsId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO goods (id, festival_id, option_mode, price_amount, created_at, updated_at)
            VALUES (:id, :festivalId, 'SINGLE', 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("id", goodsId, "festivalId", FESTIVAL_ID));
        jdbc.update("""
            INSERT INTO goods_translations (goods_id, locale, name)
            VALUES (:id, 'ko', '기존 상품'), (:id, 'en', 'Existing product')
            """, Map.of("id", goodsId));
        jdbc.update("""
            INSERT INTO goods_combinations (id, goods_id, color_id, size_id, availability, updated_at)
            VALUES (:id, :goodsId, NULL, NULL, 'ON_SALE', CURRENT_TIMESTAMP)
            """, Map.of("id", UUID.randomUUID(), "goodsId", goodsId));
        return goodsId;
    }

    private void assertProductAudit(UUID goodsId) {
        assertThat(jdbc.queryForMap("""
            SELECT action, resource_type, resource_id
            FROM admin_audit_events WHERE action = 'PRODUCT_CREATED'
            """, Map.of()))
            .containsEntry("action", "PRODUCT_CREATED")
            .containsEntry("resource_type", "GOODS")
            .containsEntry("resource_id", goodsId.toString());
    }

    private void assertNoCreatedProductState() {
        for (String table : List.of(
            "goods", "goods_translations", "goods_colors", "goods_color_translations",
            "goods_sizes", "goods_size_translations", "goods_combinations",
            "goods_images", "goods_image_translations"
        )) {
            assertThat(countRows(table)).as(table).isZero();
        }
        assertThat(productAuditCount()).isZero();
    }

    private void assertMediaUnattached(UUID mediaId) {
        Map<String, Object> lifecycle = jdbc.queryForMap(
            "SELECT attached_at, detached_at FROM media_assets WHERE id = :id", Map.of("id", mediaId)
        );
        assertThat(lifecycle.get("attached_at")).isNull();
        assertThat(lifecycle.get("detached_at")).isNull();
    }

    private List<UUID> orderedIds(String table, UUID goodsId) {
        return jdbc.queryForList(
            "SELECT id FROM " + table + " WHERE goods_id = :id ORDER BY sort_order",
            Map.of("id", goodsId), UUID.class
        );
    }

    private long count(String table, String column, UUID value) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = :id",
            Map.of("id", value), Long.class
        );
        return count == null ? 0 : count;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private long productAuditCount() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE action = 'PRODUCT_CREATED'",
            Map.of(), Long.class
        );
        return count == null ? 0 : count;
    }

    private void deleteBusinessRows() {
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

    private static byte[] mediaBytes(MediaVariant variant) {
        return ("webp-product-" + variant.name()).getBytes(StandardCharsets.UTF_8);
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

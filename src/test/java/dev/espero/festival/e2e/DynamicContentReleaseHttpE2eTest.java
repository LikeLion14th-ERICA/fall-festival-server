package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.zaxxer.hikari.HikariDataSource;
import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogCliRunner;
import dev.espero.festival.media.GoodsImageProcessor;
import dev.espero.festival.media.MediaProcessingBusyException;
import dev.espero.festival.media.MediaStorage;
import dev.espero.festival.media.ReleaseMediaTestConfiguration;
import dev.espero.festival.support.PostgresTestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP-25..27: real login, candidate publication, HTTP, PostgreSQL and filesystem lifecycle. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "spring.flyway.enabled=false",
    "festival.admin-auth.jwt-signing-secret=dynamic-content-e2e-test-signing-secret-32-bytes",
    "festival.admin-auth.allowed-origin=https://admin.dynamic.test",
    "festival.admin-auth.bootstrap-password=dynamic-content-test-password",
    "festival.rate-limit.enabled=false", "festival.public-locales=ko",
    "festival.cleanup.schedule-enabled=false", "festival.cleanup.dry-run=true",
    "festival.cleanup.datasource.url=", "festival.cleanup.datasource.username=",
    "festival.cleanup.datasource.password=", "festival.cleanup.datasource.role=",
    "spring.config.import=",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
    // Let one oversize upload reach the application spool limit; a larger one tests the parser limit.
    "spring.servlet.multipart.max-file-size=11534336B",
    "spring.servlet.multipart.max-request-size=12582912B",
    "server.tomcat.max-swallow-size=16777216B"
})
@ActiveProfiles("db")
@Import({DynamicContentReleaseHttpE2eTest.Configuration.class, ReleaseMediaTestConfiguration.class})
@Testcontainers
@Timeout(120)
class DynamicContentReleaseHttpE2eTest {

    private static final String RUN = UUID.randomUUID().toString();
    private static final UUID FESTIVAL = UUID.randomUUID();
    private static final String ADMIN = "dynamic-" + RUN;
    private static final String UPLOAD = "/api/v2/admin/media/goods-images";
    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "release-content-" + RUN);
    private static final Path MEDIA = ROOT.resolve("media");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MutableClock CLOCK = new MutableClock();
    private static boolean prepared;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        prepareCandidate();
        registry.add("festival.id", FESTIVAL::toString);
        registry.add("festival.admin-auth.bootstrap-username", () -> ADMIN);
        registry.add("festival.media.storage-root", MEDIA::toString);
        registry.add("spring.servlet.multipart.location", () -> ROOT.resolve("multipart").toString());
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean(name = "releaseGoodsImageProcessor")
    private GoodsImageProcessor processor;
    @MockitoSpyBean
    private MediaStorage storage;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    @BeforeEach
    void resetCase() throws Exception {
        reset(processor, storage);
        CLOCK.now = Instant.parse("2026-09-29T08:00:00Z");
        // This database belongs only to this class; preserve candidate/catalog and real-login rows.
        for (String table : List.of("goods_images", "goods", "media_assets", "notices",
            "admin_audit_events", "admin_idempotency_records")) {
            jdbc.update("DELETE FROM " + table);
        }
        deleteTree(MEDIA);
        Files.createDirectories(MEDIA.resolve(".staging"));
        HttpResponse<String> login = send(request("/api/v2/admin/sessions")
            .header("Origin", "https://admin.dynamic.test").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                "username", ADMIN, "password", "dynamic-content-test-password"
            )))).build());
        success(login, 200);
        token = body(login).path("data").path("accessToken").asString();
        assertThat(token.isBlank()).isFalse();
        assertThat(get("/readyz").statusCode()).isEqualTo(200);
        HttpResponse<String> config = get("/api/v2/config");
        success(config, 200);
        assertThat(body(config).path("meta").path("festivalId").asString()).isEqualTo(FESTIVAL.toString());
        assertThat(body(config).path("meta").path("revision").asLong()).isPositive();
    }

    @AfterAll
    static void cleanTemporaryFiles() throws IOException {
        deleteTree(ROOT);
    }

    @AfterEach
    void closeHttpClient() {
        http.close();
    }

    @Test
    void noticeLifecycleReplaysOnceRejectsStaleWritesAndRemovesPublicVisibility() throws Exception {
        HttpResponse<String> baseline = get("/api/v2/notices");
        success(baseline, 200);
        assertThat(body(baseline).path("data").path("items").size()).isZero();
        conditional("/api/v2/notices", etag(baseline));
        String payload = notice("initial");
        HttpRequest create = mutation("POST", "/api/v2/admin/notices", key(), null, payload);
        HttpResponse<String> first = send(create);
        success(first, 201);
        String id = id(first);
        String route = "/api/v2/admin/notices/" + id;
        assertThat(header(first, "Location")).isEqualTo(route);
        assertNoticeCounts(id, 2, 1);
        assertThat(audit("NOTICE_CREATED")).isOne();
        assertThat(completions()).isOne();
        HttpResponse<String> adminNotices = send(admin("/api/v2/admin/notices").GET().build());
        success(adminNotices, 200);
        JsonNode adminNotice = body(adminNotices).path("data").path("items").get(0);
        assertThat(adminNotice.path("id").asString()).isEqualTo(id);
        assertThat(adminNotice.path("type").asString()).isEqualTo("GENERAL");
        assertThat(adminNotice.path("translations").path("ko").path("title").asString())
            .isEqualTo("initial-ko-" + RUN);
        Map<String, Object> created = noticeRow(id);
        var createdState = noticeState(id);
        HttpResponse<String> replay = send(create);
        replay(first, replay, 201);
        assertThat(header(replay, "Location")).isEqualTo(route);
        assertThat(noticeState(id)).isEqualTo(createdState);
        HttpResponse<String> visible = get("/api/v2/notices");
        success(visible, 200);
        assertThat(etag(visible)).isNotEqualTo(etag(baseline));
        assertVisibleNotice(visible, id, "initial");
        conditional("/api/v2/notices", etag(visible));
        error(send(mutation("POST", "/api/v2/admin/notices", create.headers()
            .firstValue("Idempotency-Key").orElseThrow(), null, notice("reused"))), 409, "IDEMPOTENCY_KEY_REUSED");
        assertThat(noticeState(id)).isEqualTo(createdState);
        assertThat(etag(get("/api/v2/notices"))).isEqualTo(etag(visible));

        String versionA = adminEtag(route);
        CLOCK.advance();
        success(send(mutation("PUT", route, key(), versionA, notice("updated"))), 200);
        assertThat(noticeRow(id).get("created_at")).isEqualTo(created.get("created_at"));
        assertThat(noticeRow(id).get("updated_at")).isNotEqualTo(created.get("updated_at"));
        assertNoticeCounts(id, 2, 1);
        assertThat(jdbc.queryForList("SELECT title FROM notice_translations WHERE notice_id = ?", String.class,
            UUID.fromString(id))).containsExactlyInAnyOrder("updated-ko-" + RUN, "updated-en-" + RUN);
        assertThat(jdbc.queryForObject("SELECT url FROM notice_links WHERE notice_id = ?", String.class,
            UUID.fromString(id))).isEqualTo("https://notice.test/" + RUN + "/updated");
        var updatedState = noticeState(id);
        error(send(mutation("PUT", route, key(), versionA, notice("stale"))), 409, "EDIT_CONFLICT");
        assertThat(noticeState(id)).isEqualTo(updatedState);
        HttpResponse<String> updated = get("/api/v2/notices");
        assertThat(etag(updated)).isNotEqualTo(etag(visible));
        assertVisibleNotice(updated, id, "updated");

        String versionB = adminEtag(route);
        CLOCK.advance();
        HttpRequest delete = mutation("DELETE", route, key(), versionB, null);
        HttpResponse<String> deleted = send(delete);
        success(deleted, 200);
        var deletedState = noticeState(id);
        replay(deleted, send(delete), 200);
        error(send(mutation("DELETE", route, key(), versionB, null)), 409, "ALREADY_DELETED");
        assertThat(noticeState(id)).isEqualTo(deletedState);
        HttpResponse<String> after = get("/api/v2/notices");
        success(after, 200);
        assertThat(etag(after)).isNotEqualTo(etag(updated));
        assertThat(body(after).path("data").path("visibleIds").isEmpty()).isTrue();
        assertThat(body(after).path("data").path("items").isEmpty()).isTrue();
        error(send(admin(route).GET().build()), 404, "NOT_FOUND");
        assertThat(noticeRow(id).get("deleted_at")).isNotNull();
        assertThat(noticeRow(id).get("created_at")).isEqualTo(created.get("created_at"));
        assertNoticeCounts(id, 2, 1);
        for (String action : List.of("NOTICE_CREATED", "NOTICE_UPDATED", "NOTICE_DELETED")) {
            assertThat(audit(action)).isOne();
        }
        assertThat(completions()).isEqualTo(3);
    }

    @Test
    void goodsAvailabilityAndOptionEditsPreserveRetainedStateThenDetachOnHardDelete() throws Exception {
        success(get("/api/v2/goods"), 200);
        HttpResponse<String> baseline = get("/api/v2/goods-availability");
        success(baseline, 200);
        conditional("/api/v2/goods-availability", etag(baseline));
        String media = upload(png(0xff336699), key());
        Options options = new Options(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String payload = product(media, options, false, 1000);
        HttpRequest create = mutation("POST", "/api/v2/admin/products", key(), null, payload);
        HttpResponse<String> first = send(create);
        success(first, 201);
        String id = id(first);
        String route = "/api/v2/admin/products/" + id;
        assertThat(header(first, "Location")).isEqualTo(route);
        var initial = goodsState(id);
        HttpResponse<String> replay = send(create);
        replay(first, replay, 201);
        assertThat(header(replay, "Location")).isEqualTo(route);
        error(send(mutation("POST", "/api/v2/admin/products", create.headers()
            .firstValue("Idempotency-Key").orElseThrow(), null, product(media, options, false, 2000))),
            409, "IDEMPOTENCY_KEY_REUSED");
        assertThat(goodsState(id)).isEqualTo(initial);
        assertThat(audit("PRODUCT_CREATED")).isOne();
        assertThat(count("goods_images")).isOne();
        assertThat(count("goods_translations")).isEqualTo(2);
        assertThat(count("goods_colors")).isEqualTo(2);
        assertThat(count("goods_sizes")).isEqualTo(2);
        HttpResponse<String> adminProducts = send(admin("/api/v2/admin/products").GET().build());
        success(adminProducts, 200);
        JsonNode adminProduct = body(adminProducts).path("data").path("items").get(0);
        assertThat(adminProduct.path("id").asString()).isEqualTo(id);
        assertThat(adminProduct.path("translations").path("ko").path("name").asString())
            .isEqualTo("테스트 상품 " + RUN);
        assertThat(adminProduct.path("combinations")).hasSize(3);
        HttpResponse<String> adminGoods = send(admin("/api/v2/admin/goods").GET().build());
        success(adminGoods, 200);
        JsonNode adminGoodsItem = body(adminGoods).path("data").path("items").get(0);
        assertThat(adminGoodsItem.path("goodsId").asString()).isEqualTo(id);
        assertThat(adminGoodsItem.path("allSoldOut").asBoolean()).isFalse();
        assertThat(adminGoodsItem.path("combinations")).hasSize(3);
        assertThat(adminGoodsItem.path("combinations")).allSatisfy(combination ->
            assertThat(combination.path("status").asString()).isEqualTo("ON_SALE"));
        HttpResponse<String> list = get("/api/v2/goods");
        success(list, 200);
        assertThat(body(list).path("data").path("items").get(0).path("id").asString()).isEqualTo(id);
        HttpResponse<String> detail = get("/api/v2/goods/" + id);
        success(detail, 200);
        assertThat(body(detail).path("data").path("images").get(0).path("masterUrl").asString())
            .isEqualTo(mediaPath(media, "master"));
        assertAvailability(id, false, 3);
        assertThat(etag(get("/api/v2/goods-availability"))).isNotEqualTo(etag(baseline));
        List<Map<String, Object>> combinations = combinations(id);
        assertThat(combinations).allSatisfy(row -> assertThat(row.get("availability")).isEqualTo("ON_SALE"));
        Object productUpdatedAt = goodsRow(id).get("updated_at");
        for (Map<String, Object> combination : combinations) {
            String combinationId = combination.get("id").toString();
            var before = combinations(id);
            CLOCK.advance();
            HttpRequest update = mutation("PUT", availabilityRoute(id, combinationId), key(), null,
                "{\"status\":\"SOLD_OUT\"}");
            HttpResponse<String> changed = send(update);
            success(changed, 200);
            assertThat(goodsRow(id).get("updated_at")).isEqualTo(productUpdatedAt);
            assertOtherCombinationsUnchanged(id, combinationId, before);
            if (combination == combinations.getLast()) {
                var sold = goodsState(id);
                replay(changed, send(update), 200);
                assertThat(goodsState(id)).isEqualTo(sold);
            }
        }
        assertAvailability(id, true, 3);
        String soldEtag = etag(get("/api/v2/goods-availability"));
        String restoredId = combinations.getFirst().get("id").toString();
        var beforeRestore = combinations(id);
        CLOCK.advance();
        String restoreKey = key();
        success(send(mutation("PUT", availabilityRoute(id, restoredId), restoreKey, null,
            "{\"status\":\"ON_SALE\"}")), 200);
        assertOtherCombinationsUnchanged(id, restoredId, beforeRestore);
        assertThat(combinations(id)).filteredOn(row -> row.get("id").toString().equals(restoredId))
            .singleElement().satisfies(row -> assertThat(row.get("availability")).isEqualTo("ON_SALE"));
        var restored = goodsState(id);
        error(send(mutation("PUT", availabilityRoute(id, restoredId), restoreKey, null,
            "{\"status\":\"SOLD_OUT\"}")), 409, "IDEMPOTENCY_KEY_REUSED");
        assertThat(goodsState(id)).isEqualTo(restored);
        HttpResponse<String> available = send(request("/api/v2/goods-availability")
            .header("If-None-Match", soldEtag).GET().build());
        success(available, 200);
        assertThat(etag(available)).isNotEqualTo(soldEtag);
        assertAvailability(id, false, 3);
        assertThat(audit("GOODS_AVAILABILITY_UPDATED")).isEqualTo(4);
        assertThat(goodsRow(id).get("updated_at")).isEqualTo(productUpdatedAt);

        String versionC = adminEtag(route);
        var beforeEdit = combinations(id);
        CLOCK.advance();
        success(send(mutation("PUT", route, key(), versionC, product(media, options, true, 2000))), 200);
        List<Map<String, Object>> afterEdit = combinations(id);
        for (Map<String, Object> previous : beforeEdit) {
            if (previous.get("color_id").equals(options.blue()) && previous.get("size_id").equals(options.large())) {
                assertThat(afterEdit).noneMatch(row -> row.get("id").equals(previous.get("id")));
                error(send(mutation("PUT", availabilityRoute(id, previous.get("id").toString()), key(), null,
                    "{\"status\":\"ON_SALE\"}")), 404, "NOT_FOUND");
            } else {
                assertThat(afterEdit).contains(previous);
            }
        }
        assertThat(afterEdit).filteredOn(row -> row.get("color_id").equals(options.red())
            && row.get("size_id").equals(options.large())).singleElement().satisfies(row -> {
                assertThat(row.get("availability")).isEqualTo("ON_SALE");
                assertThat(beforeEdit).noneMatch(old -> old.get("id").equals(row.get("id")));
            });
        var edited = goodsState(id);
        error(send(mutation("PUT", route, key(), versionC, product(media, options, true, 3000))), 409, "EDIT_CONFLICT");
        assertThat(goodsState(id)).isEqualTo(edited);
        Object attachedAt = asset(media).get("attached_at");
        deleteProduct(id, true);
        assertDetached(media);
        assertThat(asset(media).get("attached_at")).isEqualTo(attachedAt);
        assertFiles(3);
        assertThat(audit("PRODUCT_UPDATED")).isOne();
        assertThat(audit("PRODUCT_DELETED")).isOne();
        assertThat(completions()).isEqualTo(8); // Upload, create, four availability writes, edit and delete.
    }

    @Test
    void mediaUploadReplayDeliveryValidatorsReplacementAndDetachHaveNoOrphanFiles() throws Exception {
        byte[] bytes = png(0xff123456);
        HttpRequest upload = multipart(token, key(), List.of(new Part("file", bytes)), false);
        HttpResponse<String> first = send(upload);
        success(first, 201);
        String mediaA = body(first).path("data").path("mediaId").asString();
        UUID.fromString(mediaA);
        var initial = mediaState();
        replay(first, send(upload), 201);
        error(send(multipart(token, upload.headers().firstValue("Idempotency-Key").orElseThrow(),
            List.of(new Part("file", png(0xff654321))), false)), 409, "IDEMPOTENCY_KEY_REUSED");
        assertThat(mediaState()).isEqualTo(initial);
        assertFiles(3);
        assertThat(audit("GOODS_IMAGE_UPLOADED")).isOne();
        assertThat(completions()).isOne();
        error(get(mediaPath(mediaA, "master")), 404, "NOT_FOUND");
        Map<String, Object> unattached = asset(mediaA);
        assertThat(unattached.get("attached_at")).isNull();
        assertThat(unattached.get("detached_at")).isNull();
        HttpResponse<String> created = send(mutation("POST", "/api/v2/admin/products", key(), null,
            product(mediaA, null, false, 1000)));
        success(created, 201);
        String id = id(created);
        assertAttached(mediaA);
        Object attachedA = asset(mediaA).get("attached_at");
        assertVariants(created, mediaA);

        // A matching validator must not conceal a missing backing file.
        String masterEtag = header(binary(mediaPath(mediaA, "master"), null), "ETag");
        Path master = finalFile(mediaA, "master");
        byte[] finalBytes = Files.readAllBytes(master);
        Files.delete(master);
        try {
            error(send(request(mediaPath(mediaA, "master")).header("If-None-Match", masterEtag).GET().build()),
                503, "SERVICE_UNAVAILABLE");
        } finally {
            Files.write(master, finalBytes);
        }

        CLOCK.advance();
        String mediaB = upload(png(0xff654321), key());
        error(get(mediaPath(mediaB, "master")), 404, "NOT_FOUND");
        CLOCK.advance();
        String route = "/api/v2/admin/products/" + id;
        HttpResponse<String> replaced = send(mutation("PUT", route, key(), adminEtag(route),
            product(mediaB, null, false, 1000)));
        success(replaced, 200);
        assertDetached(mediaA);
        assertThat(asset(mediaA).get("attached_at")).isEqualTo(attachedA);
        assertAttached(mediaB);
        Object attachedB = asset(mediaB).get("attached_at");
        assertVariants(replaced, mediaB);
        assertFiles(6);
        deleteProduct(id, false);
        assertDetached(mediaB);
        assertThat(asset(mediaB).get("attached_at")).isEqualTo(attachedB);
        var detached = mediaState();
        error(get(mediaPath(mediaB, "unknown")), 404, "NOT_FOUND");
        error(get(mediaPath(mediaB, "master") + "?download=true"), 400, "INVALID_QUERY");
        assertThat(mediaState()).isEqualTo(detached);
        assertFiles(6);
        assertThat(audit("GOODS_IMAGE_UPLOADED")).isEqualTo(2);
        assertThat(completions()).isEqualTo(5);
    }

    @Test
    void multipartAuthenticationValidationAndBothSizeLimitsLeaveNoPersistentState() throws Exception {
        byte[] image = png(0xff123456);
        List<Part> file = List.of(new Part("file", image));
        error(send(multipart(null, key(), file, false)), 401, "UNAUTHORIZED");
        error(send(multipart("malformed", key(), file, false)), 401, "UNAUTHORIZED");
        error(send(multipart(token, null, file, false)), 428, "IDEMPOTENCY_KEY_REQUIRED");
        error(send(multipart(token, "invalid key", file, false)), 400, "INVALID_IDEMPOTENCY_KEY");
        error(send(multipart(token, key(), file, true)), 400, "INVALID_IDEMPOTENCY_KEY");
        error(send(mutation("POST", UPLOAD, key(), null, "{}")), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertNoMediaMutation();
        for (List<Part> parts : List.of(List.<Part>of(), List.of(new Part("file", new byte[0])),
            List.of(new Part("file", image), new Part("file", image)),
            List.of(new Part("file", image), new Part("extra", image)),
            List.of(new Part("file", image), new Part("field", null)))) {
            error(send(multipart(token, key(), parts, false)), 422, "VALIDATION_FAILED");
            assertNoMediaMutation();
        }
        for (int size : List.of(10 * 1024 * 1024 + 1, 12 * 1024 * 1024 + 1)) {
            error(send(multipart(token, key(), List.of(new Part("file", new byte[size])), false)),
                413, "PAYLOAD_TOO_LARGE");
            assertNoMediaMutation();
        }
    }

    @Test
    void processorBackpressureAndInfrastructureFailuresRollbackAndAllowRecovery() throws Exception {
        byte[] image = png(0xff123456);
        doThrow(new MediaProcessingBusyException(Duration.ofSeconds(1))).when(processor).process(any(), any(), any());
        HttpResponse<String> busy = send(multipart(token, key(), List.of(new Part("file", image)), false));
        error(busy, 429, "RATE_LIMITED");
        assertThat(Integer.parseInt(header(busy, "Retry-After"))).isPositive();
        assertNoMediaMutation();
        reset(processor);
        // Fail after a real variant has been written, so the processor must discard partial staging.
        doAnswer(invocation -> {
            if (invocation.getArgument(1) == dev.espero.festival.media.MediaVariant.THUMB_640) {
                throw new IOException("synthetic release staging failure");
            }
            return invocation.callRealMethod();
        }).when(storage).openStagingOutput(any(), any());
        error(send(multipart(token, key(), List.of(new Part("file", image)), false)), 503, "SERVICE_UNAVAILABLE");
        assertNoMediaMutation();
        reset(storage);
        // Simulate a failure after the atomic move: both transaction rollback and final cleanup must run.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IOException("synthetic release finalization failure");
        }).when(storage).finalizeStaging(any(), any(), any());
        String finalizationKey = key();
        error(send(multipart(token, finalizationKey, List.of(new Part("file", image)), false)),
            503, "SERVICE_UNAVAILABLE");
        // Reservation is committed independently before finalization; business state still rolls back.
        assertNoMediaMutation(1);
        reset(storage);
        error(send(multipart(token, finalizationKey, List.of(new Part("file", image)), false)),
            409, "IDEMPOTENCY_IN_PROGRESS");
        assertNoMediaMutation(1);
        CLOCK.advance(Duration.ofMinutes(2).plusSeconds(1));
        upload(image, finalizationKey);
        assertThat(count("media_assets")).isOne();
        assertThat(completions()).isOne();
        assertFiles(3);
    }

    private String notice(String label) {
        return """
            {"type":"GENERAL","translations":{
              "ko":{"title":"%1$s-ko-%2$s","body":"%1$s body ko"},
              "en":{"title":"%1$s-en-%2$s","body":"%1$s body en"}},
             "links":[{"url":"https://notice.test/%2$s/%1$s",
               "labels":{"ko":"%1$s-ko","en":"%1$s-en","zh-Hans":null,"ja":null}}]}
            """.formatted(label, RUN);
    }

    private String product(String media, Options options, boolean edited, int price) {
        List<?> colors = options == null ? List.of() : List.of(
            Map.of("id", options.blue(), "translations", Map.of("ko", Map.of("name", "파랑"), "en", Map.of("name", "Blue"))),
            Map.of("id", options.red(), "translations", Map.of("ko", Map.of("name", "빨강"), "en", Map.of("name", "Red"))));
        List<?> sizes = options == null ? List.of() : List.of(
            Map.of("id", options.small(), "translations", Map.of("ko", Map.of("label", "S"), "en", Map.of("label", "S"))),
            Map.of("id", options.large(), "translations", Map.of("ko", Map.of("label", "2XL"), "en", Map.of("label", "2XL"))));
        List<?> combinations = options == null ? List.of() : List.of(
            Map.of("colorId", options.blue(), "sizeId", options.small()),
            Map.of("colorId", edited ? options.red() : options.blue(), "sizeId", options.large()),
            Map.of("colorId", options.red(), "sizeId", options.small()));
        return JSON.writeValueAsString(Map.of(
            "optionMode", options == null ? "SINGLE" : "OPTIONS",
            "translations", Map.of("ko", Map.of("name", "테스트 상품 " + RUN, "description", "시험용"),
                "en", Map.of("name", "Test goods " + RUN, "description", "Synthetic fixture")),
            "price", Map.of("amount", price, "currency", "KRW"),
            "images", List.of(Map.of("mediaId", media, "alt", Map.of("ko", "시험 이미지", "en", "Test image"))),
            "colors", colors, "sizes", sizes, "options", combinations));
    }

    private void assertVisibleNotice(HttpResponse<String> response, String id, String title) {
        assertThat(body(response).path("data").path("visibleIds").get(0).asString()).isEqualTo(id);
        JsonNode notice = body(response).path("data").path("items").get(0);
        assertThat(notice.path("id").asString()).isEqualTo(id);
        assertThat(notice.path("title").asString()).isEqualTo(title + "-ko-" + RUN);
    }

    private void assertNoticeCounts(String id, int translations, int links) {
        UUID notice = UUID.fromString(id);
        assertThat(count("notices")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notice_translations WHERE notice_id = ?", Long.class, notice))
            .isEqualTo((long) translations);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notice_links WHERE notice_id = ?", Long.class, notice))
            .isEqualTo((long) links);
        assertThat(count("notice_link_translations")).isEqualTo((long) translations * links);
    }

    private void assertAvailability(String id, boolean soldOut, int expectedCount) throws Exception {
        HttpResponse<String> response = get("/api/v2/goods/" + id + "/availability");
        success(response, 200);
        assertThat(body(response).path("data").path("allSoldOut").asBoolean()).isEqualTo(soldOut);
        assertThat(body(response).path("data").path("combinations").size()).isEqualTo(expectedCount);
        success(get("/api/v2/goods/" + id), 200);
        HttpResponse<String> list = get("/api/v2/goods-availability");
        success(list, 200);
        assertThat(body(list).path("data").path("items").get(0).path("allSoldOut").asBoolean()).isEqualTo(soldOut);
    }

    private void assertOtherCombinationsUnchanged(String id, String changed, List<Map<String, Object>> before) {
        assertThat(combinations(id).stream().filter(row -> !row.get("id").toString().equals(changed)).toList())
            .isEqualTo(before.stream().filter(row -> !row.get("id").toString().equals(changed)).toList());
        Map<String, Object> old = before.stream().filter(row -> row.get("id").toString().equals(changed)).findFirst().orElseThrow();
        Map<String, Object> current = combinations(id).stream().filter(row -> row.get("id").toString().equals(changed)).findFirst().orElseThrow();
        assertThat(current.get("updated_at")).isNotEqualTo(old.get("updated_at"));
    }

    private void deleteProduct(String id, boolean replayDelete) throws Exception {
        String route = "/api/v2/admin/products/" + id;
        String current = adminEtag(route);
        CLOCK.advance();
        HttpRequest request = mutation("DELETE", route, key(), current, null);
        HttpResponse<String> deleted = send(request);
        success(deleted, 200);
        var state = mediaState();
        if (replayDelete) {
            replay(deleted, send(request), 200);
            error(send(mutation("DELETE", route, key(), current, null)), 404, "NOT_FOUND");
            assertThat(mediaState()).isEqualTo(state);
        }
        error(get("/api/v2/goods/" + id), 404, "NOT_FOUND");
        error(send(admin(route).GET().build()), 404, "NOT_FOUND");
        for (String table : List.of("goods", "goods_translations", "goods_colors", "goods_color_translations",
            "goods_sizes", "goods_size_translations", "goods_combinations", "goods_images", "goods_image_translations")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private void assertVariants(HttpResponse<String> product, String media) throws Exception {
        JsonNode image = body(product).path("data").path("images").get(0);
        for (String variant : List.of("master", "320", "640")) {
            String url = mediaPath(media, variant);
            String field = variant.equals("master") ? "masterUrl" : "thumbnail" + variant + "Url";
            assertThat(image.path(field).asString()).isEqualTo(url);
            HttpResponse<byte[]> first = binary(url, null);
            assertThat(first.statusCode()).isEqualTo(200);
            assertMediaHeaders(first, true);
            assertThat(first.body()).isEqualTo(Files.readAllBytes(finalFile(media, variant)));
            assertThat(first.body().length).isPositive();
            String tag = header(first, "ETag");
            assertThat(tag).startsWith("\"").endsWith("\"").doesNotStartWith("W/");
            for (String validator : List.of(tag, "W/" + tag, "\"other\", W/" + tag, "*")) {
                HttpResponse<byte[]> cached = binary(url, validator);
                assertThat(cached.statusCode()).isEqualTo(304);
                assertThat(cached.body()).isEmpty();
                assertThat(header(cached, "ETag")).isEqualTo(tag);
                assertMediaHeaders(cached, false);
            }
        }
    }

    private void assertMediaHeaders(HttpResponse<?> response, boolean representation) {
        if (representation) {
            assertThat(header(response, "Content-Type")).isEqualTo("image/webp");
            assertThat(header(response, "Content-Disposition")).isEqualTo("inline");
        }
        assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(header(response, "Cache-Control")).contains("public", "max-age=31536000", "immutable");
        assertThat(header(response, "X-Request-Id")).isNotBlank();
    }

    private void assertAttached(String media) {
        assertThat(asset(media).get("attached_at")).isNotNull();
        assertThat(asset(media).get("detached_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goods_images WHERE media_id = ?", Long.class,
            UUID.fromString(media))).isOne();
    }

    private void assertDetached(String media) throws Exception {
        assertThat(asset(media).get("attached_at")).isNotNull();
        assertThat(asset(media).get("detached_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goods_images WHERE media_id = ?", Long.class,
            UUID.fromString(media))).isZero();
        for (String variant : List.of("master", "320", "640")) {
            error(get(mediaPath(media, variant)), 404, "NOT_FOUND");
            assertThat(finalFile(media, variant)).isRegularFile();
        }
    }

    private void assertNoMediaMutation() throws IOException {
        assertNoMediaMutation(0);
    }

    private void assertNoMediaMutation(long inProgressReservations) throws IOException {
        assertThat(count("media_assets")).isZero();
        assertThat(count("goods_images")).isZero();
        assertThat(count("admin_audit_events")).isZero();
        assertThat(count("admin_idempotency_records")).isEqualTo(inProgressReservations);
        assertThat(completions()).isZero();
        assertFiles(0);
    }

    private void assertFiles(long expected) throws IOException {
        try (var files = Files.walk(MEDIA); var staging = Files.list(MEDIA.resolve(".staging"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(expected);
            assertThat(staging.count()).isZero();
        }
        for (String temporary : List.of("spool", "processing")) {
            Path directory = ROOT.resolve(temporary);
            if (Files.exists(directory)) {
                try (var entries = Files.list(directory)) {
                    assertThat(entries.count()).as(temporary).isZero();
                }
            }
        }
    }

    private Map<String, Object> noticeRow(String id) {
        return jdbc.queryForMap("SELECT * FROM notices WHERE id = ?", UUID.fromString(id));
    }

    private List<?> noticeState(String id) {
        return List.of(noticeRow(id), jdbc.queryForList("SELECT * FROM notice_translations ORDER BY notice_id, locale"),
            jdbc.queryForList("SELECT * FROM notice_links ORDER BY id"),
            jdbc.queryForList("SELECT * FROM notice_link_translations ORDER BY link_id, locale"),
            count("admin_audit_events"), completions());
    }

    private Map<String, Object> goodsRow(String id) {
        return jdbc.queryForMap("SELECT * FROM goods WHERE id = ?", UUID.fromString(id));
    }

    private List<Map<String, Object>> combinations(String id) {
        return jdbc.queryForList("SELECT * FROM goods_combinations WHERE goods_id = ? ORDER BY id", UUID.fromString(id));
    }

    private List<?> goodsState(String id) {
        return List.of(goodsRow(id), combinations(id), jdbc.queryForList("SELECT * FROM goods_images ORDER BY media_id"),
            jdbc.queryForList("SELECT * FROM goods_translations ORDER BY goods_id, locale"),
            jdbc.queryForList("SELECT * FROM goods_colors ORDER BY id"),
            jdbc.queryForList("SELECT * FROM goods_sizes ORDER BY id"),
            jdbc.queryForList("SELECT * FROM goods_color_translations ORDER BY color_id, locale"),
            jdbc.queryForList("SELECT * FROM goods_size_translations ORDER BY size_id, locale"),
            jdbc.queryForList("SELECT * FROM goods_image_translations ORDER BY media_id, locale"),
            count("admin_audit_events"), completions());
    }

    private List<?> mediaState() {
        return List.of(jdbc.queryForList("SELECT * FROM media_assets ORDER BY id"),
            jdbc.queryForList("SELECT * FROM goods_images ORDER BY media_id"), count("admin_audit_events"), completions());
    }

    private Map<String, Object> asset(String id) {
        return jdbc.queryForMap("SELECT * FROM media_assets WHERE id = ?", UUID.fromString(id));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long audit(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = ?", Long.class, action);
    }

    private long completions() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_idempotency_records WHERE state = 'COMPLETED'", Long.class);
    }

    private Path finalFile(String media, String variant) {
        return MEDIA.resolve(asset(media).get("storage_key").toString()).resolve(variant + ".webp");
    }

    private static String mediaPath(String media, String variant) {
        return "/api/v2/media/goods-images/" + media + "/" + variant;
    }

    private static String availabilityRoute(String goods, String combination) {
        return "/api/v2/admin/goods/" + goods + "/combinations/" + combination + "/availability";
    }

    private String upload(byte[] image, String key) throws Exception {
        HttpResponse<String> response = send(multipart(token, key, List.of(new Part("file", image)), false));
        success(response, 201);
        return body(response).path("data").path("mediaId").asString();
    }

    private HttpRequest multipart(String bearer, String key, List<Part> parts, boolean duplicateKey) throws IOException {
        String boundary = "release-" + RUN;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Part part : parts) {
            bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name() + "\""
                + (part.bytes() == null ? "" : "; filename=\"" + RUN + ".png\"")
                + "\r\n" + (part.bytes() == null ? "" : "Content-Type: image/png\r\n") + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            bytes.write(part.bytes() == null ? "field".getBytes(StandardCharsets.US_ASCII) : part.bytes());
            bytes.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        bytes.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest.Builder builder = request(UPLOAD).header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (key != null) builder.header("Idempotency-Key", key);
        if (duplicateKey) builder.header("Idempotency-Key", key());
        return builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
    }

    private static byte[] png(int rgb) throws IOException {
        BufferedImage image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(rgb));
            graphics.fillRect(0, 0, 1024, 1024);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG writer unavailable");
        return bytes.toByteArray();
    }

    private HttpRequest mutation(String method, String route, String key, String etag, String payload) {
        HttpRequest.Builder builder = admin(route).header("Idempotency-Key", key);
        if (etag != null) builder.header("If-Match", etag);
        if (payload != null) builder.header("Content-Type", "application/json");
        return builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(payload)).build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
    }

    private HttpRequest.Builder admin(String path) {
        return request(path).header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String route) throws Exception {
        return send(request(route).GET().build());
    }

    private HttpResponse<byte[]> binary(String route, String validator) throws Exception {
        var builder = request(route);
        if (validator != null) builder.header("If-None-Match", validator);
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String adminEtag(String route) throws Exception {
        HttpResponse<String> response = send(admin(route).GET().build());
        success(response, 200);
        return etag(response);
    }

    private void conditional(String route, String tag) throws Exception {
        HttpResponse<String> response = send(request(route).header("If-None-Match", tag).GET().build());
        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
        assertThat(etag(response)).isEqualTo(tag);
        assertThat(header(response, "X-Request-Id")).isNotBlank();
    }

    private static void success(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.uri().getPath()).isEqualTo(status);
        assertThat(header(response, "X-Request-Id")).isNotBlank();
        JsonNode meta = body(response).path("meta");
        // Conditional representations intentionally omit request-specific metadata from JSON.
        if (meta.has("requestId")) {
            assertThat(meta.path("requestId").asString()).isEqualTo(header(response, "X-Request-Id"));
        }
    }

    private static void error(HttpResponse<String> response, int status, String code) {
        success(response, status);
        assertThat(body(response).path("error").path("code").asString()).isEqualTo(code);
        assertThat(body(response).path("meta").path("requestId").asString()).isEqualTo(header(response, "X-Request-Id"));
    }

    private static void replay(HttpResponse<String> first, HttpResponse<String> replay, int status) {
        assertThat(replay.statusCode()).isEqualTo(status);
        assertThat(header(replay, "X-Request-Id")).isNotEqualTo(header(first, "X-Request-Id"));
        assertThat(body(replay).path("data")).isEqualTo(body(first).path("data"));
        if (body(first).path("meta").has("requestId")) {
            assertThat(body(replay).path("meta").path("requestId").asString()).isNotBlank();
        }
    }

    private static String etag(HttpResponse<?> response) {
        String value = header(response, "ETag");
        assertThat(value).startsWith("\"").endsWith("\"").doesNotStartWith("W/");
        return value;
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow(() -> new AssertionError("Missing " + name));
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String id(HttpResponse<String> response) {
        return body(response).path("data").path("id").asString();
    }

    private static String key() {
        return RUN + "-" + UUID.randomUUID();
    }

    private static synchronized void prepareCandidate() throws Exception {
        if (prepared) return;
        POSTGRES.start();
        Files.createDirectories(ROOT.resolve("multipart"));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .placeholders(Map.of("festivalId", FESTIVAL.toString())).load().migrate();
        Path manifest = ROOT.resolve("candidate-" + RUN + ".json");
        Files.copy(Path.of("dev/catalog/frontend-mock-catalog.json"), manifest);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("dynamic-content-cli", Map.of(
            "spring.config.location", "classpath:/application.yml", "spring.config.import", "",
            "spring.flyway.enabled", false, "festival.id", FESTIVAL.toString(),
            "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
            "spring.main.banner-mode", "off", "logging.level.root", "WARN"
        )));
        try (ConfigurableApplicationContext cli = new SpringApplicationBuilder(CatalogCliApplication.class, Configuration.class)
            .environment(environment).profiles("db", "catalog-cli").web(WebApplicationType.NONE).run()) {
            JdbcTemplate jdbc = cli.getBean(JdbcTemplate.class);
            jdbc.update("""
                INSERT INTO festivals (id, title, timezone, created_at, updated_at)
                VALUES (?, ?, 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, FESTIVAL, "Synthetic release " + RUN);
            CatalogCliRunner runner = cli.getBean(CatalogCliRunner.class);
            runner.run(new DefaultApplicationArguments("import", "--manifest=" + manifest, "--festival-id=" + FESTIVAL,
                "--baseline-revision=none", "--actor=dynamic-content-e2e"));
            UUID draft = jdbc.queryForObject("SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'draft' ORDER BY revision_number DESC LIMIT 1",
                UUID.class, FESTIVAL);
            runner.run(new DefaultApplicationArguments("publish", "--revision=" + draft, "--actor=dynamic-content-e2e"));
            assertThat(jdbc.queryForObject("SELECT state FROM festival_revisions WHERE id = ?", String.class, draft))
                .isEqualTo("published");
        }
        prepared = true;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var entries = Files.walk(root)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }

    private record Options(UUID blue, UUID red, UUID small, UUID large) {}
    private record Part(String name, byte[] bytes) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean(name = "dataSource")
        @Primary
        DataSource containerDataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(POSTGRES.getJdbcUrl());
            source.setUsername(POSTGRES.getUsername());
            source.setPassword(POSTGRES.getPassword());
            return source;
        }

        @Bean
        @Primary
        Clock releaseClock() {
            return CLOCK;
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-29T08:00:00Z");
        void advance() { now = now.plusSeconds(2); }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}

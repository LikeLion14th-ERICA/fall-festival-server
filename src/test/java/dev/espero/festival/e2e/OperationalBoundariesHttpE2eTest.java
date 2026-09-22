package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogCliRunner;
import dev.espero.festival.FallFestivalServerApplication;
import dev.espero.festival.support.PostgresTestImages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP and child JVMs against a fresh disposable database for HTTP-28..31. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class OperationalBoundariesHttpE2eTest {
    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String ORIGIN = "https://admin.operational-e2e.test";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NOTICES = "/api/v2/admin/notices";
    private static final String TEMPLATES = "/api/v2/admin/notice-templates";
    private static final String PRODUCTS = "/api/v2/admin/products";
    private static final String MEDIA = "/api/v2/admin/media/goods-images";
    private final String username = "ops-" + UUID.randomUUID();
    private final String password = UUID.randomUUID().toString();

    @Container
    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImages.image());
    @TempDir Path directory;
    private Path mediaRoot;

    @BeforeEach
    void publishDisposableCandidate() throws Exception {
        mediaRoot = Files.createDirectory(directory.resolve("media"));
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString())).load().migrate();
        try (ConfigurableApplicationContext cli = start(false, "", false)) {
            JdbcTemplate jdbc = cli.getBean(JdbcTemplate.class);
            UUID baseline = jdbc.queryForObject(
                "SELECT id FROM festival_revisions WHERE festival_id=? AND state='published'", UUID.class, FESTIVAL_ID);
            CatalogCliRunner commands = cli.getBean(CatalogCliRunner.class);
            commands.run(new DefaultApplicationArguments("import",
                "--manifest=" + Path.of("dev/catalog/frontend-mock-catalog.json").toAbsolutePath(),
                "--festival-id=" + FESTIVAL_ID, "--baseline-revision=" + baseline, "--actor=operational-http-e2e"));
            UUID draft = jdbc.queryForObject(
                "SELECT id FROM festival_revisions WHERE festival_id=? AND state='draft'", UUID.class, FESTIVAL_ID);
            commands.run(new DefaultApplicationArguments("publish", "--revision=" + draft, "--actor=operational-http-e2e"));
        }
    }

    @Test
    void receiptRotationRateLimitsAndUnconfiguredServerPreserveDatabaseAndSecrets(CapturedOutput logs) throws Exception {
        SecureRandom random = new SecureRandom();
        String oldCode = "0" + String.format(java.util.Locale.ROOT, "%05d", random.nextInt(100000));
        String newCode = "1" + String.format(java.util.Locale.ROOT, "%05d", random.nextInt(100000));
        String wrongCode = "2" + String.format(java.util.Locale.ROOT, "%05d", random.nextInt(100000));
        List<String> secrets = List.of(oldCode, newCode, wrongCode, sha256(oldCode), sha256(newCode));
        try (HttpClient http = client(); ConfigurableApplicationContext server = start(true, sha256(oldCode) + "," + sha256(newCode), true)) {
            JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
            // A correct code claims the reward of a full stamp card, so each success needs its own card.
            List<String> cards = List.of(fullCard(jdbc), fullCard(jdbc), fullCard(jdbc));
            Map<String, String> before = state(jdbc);
            int card = 0;
            for (String code : List.of(oldCode, newCode)) {
                HttpResponse<String> response = receipt(http, server, code, "198.51.100.1", cards.get(card++));
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(body(response).at("/data/verified").asBoolean()).isTrue();
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                safe(response, secrets);
            }
            int identity = 10;
            for (String code : List.of("letters", "12", "1234567", " " + oldCode, oldCode + " ", wrongCode)) {
                HttpResponse<String> response = receipt(http, server, code, "198.51.100." + identity++);
                error(response, 422, "INVALID_RECEIPT_CODE", false);
                safe(response, secrets);
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                error(receipt(http, server, wrongCode, "203.0.113.20"), 422, "INVALID_RECEIPT_CODE", false);
            }
            HttpResponse<String> limited = receipt(http, server, wrongCode, "203.0.113.20");
            error(limited, 429, "RATE_LIMITED", true);
            assertThat(limited.headers().firstValue("Retry-After").orElseThrow()).matches("[1-9][0-9]*");
            HttpResponse<String> spoofed = receiptChain(http, server, wrongCode, "192.0.2.199, 203.0.113.20, 192.0.2.2");
            error(spoofed, 429, "RATE_LIMITED", true);
            error(receipt(http, server, wrongCode, "203.0.113.21"), 422, "INVALID_RECEIPT_CODE", false);
            server.getBean(MutableClock.class).advance(Duration.ofSeconds(12));
            HttpResponse<String> recovered = receipt(http, server, newCode, "203.0.113.20", cards.get(card));
            assertThat(recovered.statusCode()).isEqualTo(200);
            safe(limited, secrets);
            safe(spoofed, secrets);
            safe(recovered, secrets);
            Map<String, String> after = state(jdbc);
            before.remove("stamp_rewards");
            after.remove("stamp_rewards");
            assertThat(after).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stamp_rewards", Integer.class)).isEqualTo(3);
        }
        try (HttpClient http = client(); ConfigurableApplicationContext server = start(true, sha256(newCode), true)) {
            error(receipt(http, server, oldCode, "198.51.100.30"), 422, "INVALID_RECEIPT_CODE", false);
            String card = fullCard(server.getBean(JdbcTemplate.class));
            assertThat(receipt(http, server, newCode, "198.51.100.31", card).statusCode()).isEqualTo(200);
        }
        try (HttpClient http = client(); ConfigurableApplicationContext server = start(true, "", true)) {
            Map<String, String> before = state(server.getBean(JdbcTemplate.class));
            HttpResponse<String> unavailable = receipt(http, server, newCode, "198.51.100.32");
            error(unavailable, 503, "STAMP_RECEIPT_UNCONFIGURED", false);
            safe(unavailable, secrets);
            assertThat(state(server.getBean(JdbcTemplate.class))).isEqualTo(before);
        }
        noSecrets(logs.getAll(), secrets);
    }

    @Test
    void goodsAccountCliDryRunSetStaleClearAndRestartPropagateWithoutTicketSideEffects() throws Exception {
        String account = "11" + String.format(java.util.Locale.ROOT, "%010d", new SecureRandom().nextLong(10_000_000_000L));
        String bank = "TestBank-" + UUID.randomUUID();
        String holder = "TestHolder-" + UUID.randomUUID();
        Path input = directory.resolve("account.json");
        Files.writeString(input, JSON.writeValueAsString(Map.of("bankName", bank, "accountNumber", account, "accountHolder", holder)));
        List<UUID> goods;
        try (HttpClient http = client(); ConfigurableApplicationContext server = start(true, "", false)) {
            JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
            goods = List.of(seedGoods(jdbc), seedGoods(jdbc));
            for (UUID id : goods) goodsAccount(http, server, id, null);
            Map<String, String> before = state(jdbc);
            ProcessResult preview = accountSet(input, account, false, 0);
            success(preview);
            assertThat(preview.output()).contains("mode=DRY_RUN");
            noSecrets(preview.output(), List.of(account, bank, holder));
            assertThat(state(jdbc)).isEqualTo(before);
            for (UUID id : goods) goodsAccount(http, server, id, null);
            ProcessResult applied = accountSet(input, account, true, 0);
            success(applied);
            assertThat(applied.output()).contains("mode=APPLIED", "resultVersion=1");
            noSecrets(applied.output(), List.of(account, bank, holder));
            for (UUID id : goods) goodsAccount(http, server, id, account);
            assertThat(accountHistory(jdbc)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT version FROM operational_account_settings WHERE purpose='GOODS'", Long.class)).isEqualTo(1L);
            Map<String, String> configured = state(jdbc);
            ProcessResult stale = accountSet(input, account, true, 0);
            assertThat(stale.exitCode()).isNotZero();
            assertThat(stale.output()).contains("ACCOUNT_EXPECTED_VERSION_MISMATCH");
            noSecrets(stale.output(), List.of(account, bank, holder));
            assertThat(state(jdbc)).isEqualTo(configured);
            for (UUID id : goods) goodsAccount(http, server, id, account);
            ProcessResult clear = cli("AccountSettingsCliApplication", "clear", "--festival-id=" + FESTIVAL_ID,
                "--purpose=GOODS", "--expected-version=1", "--confirm", "--actor=operational-http-e2e",
                "--reason=release-verification", "--evidence-id=HTTP-29-clear");
            success(clear);
            assertThat(clear.output()).contains("state=UNCONFIGURED", "resultVersion=2");
            noSecrets(clear.output(), List.of(account, bank, holder));
            assertThat(accountHistory(jdbc)).isEqualTo(2L);
            for (UUID id : goods) goodsAccount(http, server, id, null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operational_account_settings WHERE purpose='TICKET'", Long.class)).isZero();
        }
        try (HttpClient http = client(); ConfigurableApplicationContext restarted = start(true, "", false)) {
            for (UUID id : goods) goodsAccount(http, restarted, id, null);
            assertThat(accountHistory(restarted.getBean(JdbcTemplate.class))).isEqualTo(2L);
        } finally {
            Files.delete(input);
        }
    }

    @Test
    void templateCliReplacementIsAtomicAndPreservesNoticesFromRemovedTemplates() throws Exception {
        String templateId = "ops-template-" + UUID.randomUUID();
        Path setA = directory.resolve("templates-a.json");
        Path setB = directory.resolve("templates-b.json");
        Path malformed = directory.resolve("templates-invalid.json");
        Path duplicate = directory.resolve("templates-duplicate.json");
        String template = JSON.writeValueAsString(Map.of("id", templateId, "name", "Release template",
            "translations", Map.of("ko", Map.of("title", "운영 테스트", "body", "운영 본문"),
                "en", Map.of("title", "Release test", "body", "Release body"))));
        Files.writeString(setA, "{\"templates\":[" + template + "]}");
        Files.writeString(setB, "{\"templates\":[]}");
        Files.writeString(malformed, "{\"templates\":");
        Files.writeString(duplicate, "{\"templates\":[" + template + "," + template + "]}");
        try (HttpClient http = client(); ConfigurableApplicationContext server = start(true, "", false)) {
            JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
            String access = login(http, server);
            Map<String, String> baseline = state(jdbc);
            ProcessResult preview = cli("NoticeTemplateCliApplication", "replace", "--input-file=" + setA);
            success(preview);
            assertThat(preview.output()).contains("Nothing was written");
            assertThat(state(jdbc)).isEqualTo(baseline);
            success(cli("NoticeTemplateCliApplication", "replace", "--input-file=" + setA, "--confirm"));
            assertThat(count(jdbc, "notice_templates")).isEqualTo(1L);
            HttpResponse<String> list = get(http, server, TEMPLATES, access);
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(body(list).at("/data/items").size()).isEqualTo(1);
            HttpResponse<String> detail = get(http, server, TEMPLATES + "/" + templateId, access);
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(body(detail).at("/data/id").asText()).isEqualTo(templateId);
            assertThat(body(detail).at("/data/translations/en/title").asText()).isEqualTo("Release test");
            for (String path : List.of(TEMPLATES, TEMPLATES + "/" + templateId)) {
                error(get(http, server, path, null), 401, "UNAUTHORIZED", false);
                error(get(http, server, path, "invalid-token"), 401, "UNAUTHORIZED", false);
                error(get(http, server, path + "?unexpected=1", access), 400, "INVALID_QUERY", false);
            }
            HttpResponse<String> created = mutate(http, server, "POST", NOTICES,
                notice(templateId, "Template notice"), access, key(), null);
            assertThat(created.statusCode()).isEqualTo(201);
            String noticeId = body(created).at("/data/id").asText();
            assertThat(created.headers().firstValue("Location")).contains(NOTICES + "/" + noticeId);
            assertThat(jdbc.queryForObject("SELECT template_id FROM notices WHERE id=?", String.class, UUID.fromString(noticeId)))
                .isEqualTo(templateId);
            JsonNode beforeNotice = body(get(http, server, NOTICES + "/" + noticeId, access)).at("/data/translations");
            JsonNode beforePublic = publicNotice(http, server, noticeId);
            String auditBefore = digest(jdbc, "admin_audit_events");
            String textBefore = digest(jdbc, "notice_translations");
            String linksBefore = digest(jdbc, "notice_links");
            success(cli("NoticeTemplateCliApplication", "replace", "--input-file=" + setB, "--confirm"));
            error(get(http, server, TEMPLATES + "/" + templateId, access), 404, "NOT_FOUND", false);
            assertThat(body(get(http, server, TEMPLATES, access)).at("/data/items").size()).isZero();
            assertThat(body(get(http, server, NOTICES + "/" + noticeId, access)).at("/data/translations")).isEqualTo(beforeNotice);
            assertThat(publicNotice(http, server, noticeId)).isEqualTo(beforePublic);
            assertThat(jdbc.queryForObject("SELECT template_id FROM notices WHERE id=?", String.class, UUID.fromString(noticeId))).isNull();
            assertThat(digest(jdbc, "admin_audit_events")).isEqualTo(auditBefore);
            assertThat(digest(jdbc, "notice_translations")).isEqualTo(textBefore);
            assertThat(digest(jdbc, "notice_links")).isEqualTo(linksBefore);
            for (Path invalid : List.of(malformed, duplicate)) {
                for (boolean confirm : List.of(false, true)) {
                    Map<String, String> before = state(jdbc);
                    List<String> args = new ArrayList<>(List.of("replace", "--input-file=" + invalid));
                    if (confirm) args.add("--confirm");
                    ProcessResult failure = cli("NoticeTemplateCliApplication", args.toArray(String[]::new));
                    assertThat(failure.exitCode()).isNotZero();
                    assertThat(failure.output()).contains(invalid.equals(duplicate) ? "TEMPLATE_ID_DUPLICATE" : "TEMPLATE_INPUT_INVALID");
                    assertThat(state(jdbc)).isEqualTo(before);
                    assertThat(body(get(http, server, TEMPLATES, access)).at("/data/items").size()).isZero();
                }
            }
        } finally {
            for (Path input : List.of(setA, setB, malformed, duplicate)) Files.delete(input);
        }
    }

    @Test
    void administratorMutationMatrixRejectsBeforeSideEffectsAndNoticeReplayIsExactlyOnce(CapturedOutput logs) throws Exception {
        int logStart = logs.getAll().length();
        try (HttpClient http = client(); ConfigurableApplicationContext server = start(true, "", false)) {
            JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
            String access = login(http, server);
            String fakeId = UUID.randomUUID().toString();
            String product = """
                {"optionMode":"SINGLE","translations":{"ko":{"name":"상품"},"en":{"name":"Product"}},
                 "price":{"amount":1000,"currency":"KRW"},"images":[{"mediaId":"%s","alt":{"ko":"사진","en":"Image"}}],
                 "colors":[],"sizes":[],"options":[]}
                """.formatted(UUID.randomUUID());
            String payload = notice(null, "Boundary notice");
            HttpResponse<String> fixtureNotice = mutate(http, server, "POST", NOTICES, payload, access, key(), null);
            assertThat(fixtureNotice.statusCode()).isEqualTo(201);
            String noticeFixtureId = body(fixtureNotice).at("/data/id").asText();
            List<Mutation> matrix = List.of(
                new Mutation("POST", NOTICES, payload, false, false),
                new Mutation("PUT", NOTICES + "/" + noticeFixtureId, payload, true, false),
                new Mutation("DELETE", NOTICES + "/" + noticeFixtureId, "", true, false),
                new Mutation("POST", PRODUCTS, product, false, false),
                new Mutation("PUT", PRODUCTS + "/" + fakeId, product, true, false),
                new Mutation("DELETE", PRODUCTS + "/" + fakeId, "", true, false),
                new Mutation("PUT", "/api/v2/admin/goods/" + fakeId + "/combinations/" + fakeId + "/availability",
                    "{\"status\":\"SOLD_OUT\"}", false, false),
                new Mutation("POST", MEDIA, multipart(), false, true));
            for (Mutation mutation : matrix) {
                for (String token : List.of("", "invalid-token")) {
                    Map<String, String> before = state(jdbc);
                    error(sendMutation(http, server, mutation, token, List.of(key()), null), 401, "UNAUTHORIZED", false);
                    unchanged(jdbc, before);
                }
                for (List<String> keys : List.of(List.<String>of(), List.of("invalid key"), List.of(key(), key()))) {
                    Map<String, String> before = state(jdbc);
                    error(sendMutation(http, server, mutation, access, keys, null), keys.isEmpty() ? 428 : 400,
                        keys.isEmpty() ? "IDEMPOTENCY_KEY_REQUIRED" : "INVALID_IDEMPOTENCY_KEY", false);
                    unchanged(jdbc, before);
                }
                if (mutation.ifMatch()) {
                    for (String etag : List.of("", "malformed")) {
                        Map<String, String> before = state(jdbc);
                        long reservations = pendingReservations(jdbc);
                        String completed = completedDigest(jdbc);
                        error(sendMutation(http, server, mutation, access, List.of(key()), etag),
                            etag.isEmpty() ? 428 : 400, etag.isEmpty() ? "PRECONDITION_REQUIRED" : "INVALID_IF_MATCH", false);
                        if (mutation.path().startsWith(NOTICES + "/")) {
                            // Notice checks If-Match after a committed short reservation transaction.
                            rejectedAfterReservation(jdbc, before, reservations, completed);
                        } else {
                            unchanged(jdbc, before);
                        }
                    }
                }
            }
            String createKey = key();
            long auditBefore = count(jdbc, "admin_audit_events");
            HttpResponse<String> created = mutate(http, server, "POST", NOTICES, payload, access, createKey, null);
            assertThat(created.statusCode()).isEqualTo(201);
            String id = body(created).at("/data/id").asText();
            Map<String, String> afterCreate = state(jdbc);
            HttpResponse<String> replay = mutate(http, server, "POST", NOTICES, payload, access, createKey, null);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(body(replay).at("/data/id").asText()).isEqualTo(id);
            assertThat(state(jdbc)).isEqualTo(afterCreate);
            error(mutate(http, server, "POST", NOTICES, notice(null, "Different"), access, createKey, null),
                409, "IDEMPOTENCY_KEY_REUSED", false);
            assertThat(state(jdbc)).isEqualTo(afterCreate);
            String path = NOTICES + "/" + id;
            String etag = get(http, server, path, access).headers().firstValue("ETag").orElseThrow();
            String updateKey = key();
            String updated = notice(null, "Updated");
            assertThat(mutate(http, server, "PUT", path, updated, access, updateKey, etag).statusCode()).isEqualTo(200);
            Map<String, String> afterUpdate = state(jdbc);
            assertThat(mutate(http, server, "PUT", path, updated, access, updateKey, etag).statusCode()).isEqualTo(200);
            assertThat(state(jdbc)).isEqualTo(afterUpdate);
            long reservations = pendingReservations(jdbc);
            String completed = completedDigest(jdbc);
            error(mutate(http, server, "PUT", path, payload, access, key(), etag), 409, "EDIT_CONFLICT", false);
            rejectedAfterReservation(jdbc, afterUpdate, reservations, completed);
            assertThat(publicNotice(http, server, id).at("/title").asText()).isEqualTo("Updated");
            String currentEtag = get(http, server, path, access).headers().firstValue("ETag").orElseThrow();
            String deleteKey = key();
            assertThat(mutate(http, server, "DELETE", path, "", access, deleteKey, currentEtag).statusCode()).isEqualTo(200);
            Map<String, String> afterDelete = state(jdbc);
            assertThat(mutate(http, server, "DELETE", path, "", access, deleteKey, currentEtag).statusCode()).isEqualTo(200);
            assertThat(state(jdbc)).isEqualTo(afterDelete);
            assertThat(count(jdbc, "admin_audit_events")).isEqualTo(auditBefore + 3);
            error(get(http, server, path, access), 404, "NOT_FOUND", false);
            for (String publicPath : List.of("/api/v2/notices", "/api/v2/goods", "/api/v2/goods-availability")) {
                assertThat(get(http, server, publicPath, null).statusCode()).isEqualTo(200);
            }
            noSecrets(logs.getAll().substring(logStart), List.of(access, password, postgres.getJdbcUrl(), mediaRoot.toString()));
        }
    }

    private ConfigurableApplicationContext start(boolean web, String hashes, boolean rateLimit) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spring.config.location", "classpath:/application.yml");
        values.put("spring.config.import", "");
        values.put("spring.datasource.url", postgres.getJdbcUrl());
        values.put("spring.datasource.username", postgres.getUsername());
        values.put("spring.datasource.password", postgres.getPassword());
        values.put("spring.datasource.hikari.jdbc-url", postgres.getJdbcUrl());
        values.put("spring.datasource.hikari.username", postgres.getUsername());
        values.put("spring.datasource.hikari.password", postgres.getPassword());
        values.put("spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration");
        values.put("spring.flyway.enabled", false);
        values.put("spring.main.banner-mode", "off");
        values.put("spring.main.log-startup-info", false);
        values.put("logging.level.root", "WARN");
        values.put("server.address", "127.0.0.1");
        values.put("server.port", 0);
        values.put("festival.id", FESTIVAL_ID.toString());
        values.put("festival.admin-auth.jwt-signing-secret", UUID.randomUUID() + "-ephemeral-signing-key");
        values.put("festival.admin-auth.allowed-origin", ORIGIN);
        values.put("festival.admin-auth.bootstrap-username", username);
        values.put("festival.admin-auth.bootstrap-password", password);
        values.put("festival.media.storage-root", mediaRoot.toString());
        values.put("festival.stamp-receipt.code-sha256", hashes);
        values.put("festival.rate-limit.enabled", rateLimit);
        values.put("festival.rate-limit.trusted-proxy-hops", 2);
        values.put("festival.cleanup.schedule-enabled", false);
        values.put("festival.cleanup.dry-run", true);
        for (String property : List.of("url", "username", "password", "role")) values.put("festival.cleanup.datasource." + property, "");
        environment.getPropertySources().addFirst(new MapPropertySource("operational-http-e2e", values));
        Class<?> source = web ? FallFestivalServerApplication.class : CatalogCliApplication.class;
        ConfigurableApplicationContext context = new SpringApplicationBuilder(source, ClockConfiguration.class)
            .environment(environment).profiles(web ? new String[] {"db"} : new String[] {"db", "catalog-cli"})
            .web(web ? WebApplicationType.SERVLET : WebApplicationType.NONE).run();
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            assertThat(connection.getMetaData().getURL().equals(postgres.getJdbcUrl())).as("disposable datasource").isTrue();
            return context;
        } catch (Exception | AssertionError failure) {
            context.close();
            throw failure;
        }
    }

    private ProcessResult cli(String application, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dspring.main.banner-mode=off", "-Dspring.main.log-startup-info=false", "-Dlogging.level.root=WARN",
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            "dev.espero.festival." + application));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        for (String name : List.of("ComSpec", "HOME", "HOMEDRIVE", "HOMEPATH", "LANG", "LC_ALL", "LOCALAPPDATA",
            "PATH", "PATHEXT", "Path", "SystemRoot", "TEMP", "TMP", "TMPDIR", "USERPROFILE", "WINDIR")) {
            if (System.getenv(name) != null) environment.put(name, System.getenv(name));
        }
        environment.put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        environment.put("SPRING_DATASOURCE_HIKARI_JDBC_URL", postgres.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_HIKARI_USERNAME", postgres.getUsername());
        environment.put("SPRING_DATASOURCE_HIKARI_PASSWORD", postgres.getPassword());
        environment.put("SPRING_CONFIG_LOCATION", "classpath:/application.yml");
        environment.put("SPRING_CONFIG_IMPORT", "");
        environment.put("SPRING_FLYWAY_ENABLED", "false");
        environment.put("FESTIVAL_ID", FESTIVAL_ID.toString());
        Process process = builder.start();
        FutureTask<String> output = new FutureTask<>(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Thread.startVirtualThread(output);
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new AssertionError("CLI exceeded its deadline");
            String text = output.get(5, TimeUnit.SECONDS);
            noSecrets(text, List.of(postgres.getPassword(), postgres.getJdbcUrl(), password));
            return new ProcessResult(process.exitValue(), text);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("CLI cleanup").isTrue();
            }
            output.cancel(true);
        }
    }

    private ProcessResult accountSet(Path input, String account, boolean confirm, long version) throws Exception {
        List<String> args = new ArrayList<>(List.of("set", "--festival-id=" + FESTIVAL_ID, "--purpose=GOODS",
            "--expected-version=" + version, "--input-file=" + input, "--last-four=" + account.substring(account.length() - 4),
            "--actor=operational-http-e2e", "--reason=release-verification", "--evidence-id=HTTP-29-set"));
        if (confirm) args.add("--confirm");
        return cli("AccountSettingsCliApplication", args.toArray(String[]::new));
    }

    private UUID seedGoods(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO goods(id,festival_id,option_mode,price_amount) VALUES (?,?,'SINGLE',1000)", id, FESTIVAL_ID);
        for (String locale : List.of("ko", "en")) {
            jdbc.update("INSERT INTO goods_translations(goods_id,locale,name) VALUES (?,?,?)", id, locale, "Account probe " + id);
        }
        return id;
    }

    private long accountHistory(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM operational_account_setting_history WHERE purpose='GOODS'", Long.class);
    }

    private void goodsAccount(HttpClient http, ConfigurableApplicationContext server, UUID id, String account) throws Exception {
        HttpResponse<String> response = get(http, server, "/api/v2/goods/" + id + "/payment-guide", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode value = body(response).at("/data/account");
        if (account == null) assertThat(value.isMissingNode() || value.isNull()).isTrue();
        else assertThat(value.path("accountNumber").asText().equals(account)).as("expected test-only account").isTrue();
    }

    private String login(HttpClient http, ConfigurableApplicationContext server) throws Exception {
        HttpResponse<String> response = http.send(request(server, "/api/v2/admin/sessions").header("Origin", ORIGIN)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                JSON.writeValueAsString(Map.of("username", username, "password", password)))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String access = body(response).at("/data/accessToken").asText();
        assertThat(!access.isBlank()).as("login credential").isTrue();
        return access;
    }

    private HttpResponse<String> receipt(HttpClient http, ConfigurableApplicationContext server, String code, String client) throws Exception {
        return receipt(http, server, code, client, null);
    }

    private HttpResponse<String> receipt(HttpClient http, ConfigurableApplicationContext server, String code, String client,
        String participant) throws Exception {
        return receiptChain(http, server, code, "192.0.2.1, " + client + ", 192.0.2.2", participant);
    }

    private HttpResponse<String> receiptChain(HttpClient http, ConfigurableApplicationContext server, String code, String chain) throws Exception {
        return receiptChain(http, server, code, chain, null);
    }

    private HttpResponse<String> receiptChain(HttpClient http, ConfigurableApplicationContext server, String code, String chain,
        String participant) throws Exception {
        HttpRequest.Builder request = request(server, "/api/v2/stamp-receipt-verifications").header("X-Forwarded-For", chain)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                JSON.writeValueAsString(Map.of("code", code))));
        if (participant != null) {
            request.header("Cookie", "__Host-festival-stamp=" + participant);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Seeds an anonymous participant holding today's four stamps and returns its cookie value. */
    private String fullCard(JdbcTemplate jdbc) throws Exception {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        UUID participant = UUID.randomUUID();
        jdbc.update("INSERT INTO stamp_participants (id, festival_id, token_sha256, created_at) VALUES (?, ?, ?, now())",
            participant, FESTIVAL_ID, sha256(token));
        for (int booth = 1; booth <= 4; booth++) {
            jdbc.update("INSERT INTO stamp_collections (participant_id, operating_date, booth_id, collected_at)"
                + " VALUES (?, DATE '2026-09-29', ?, now())", participant, "booth-" + booth);
        }
        return token;
    }

    private HttpResponse<String> mutate(HttpClient http, ConfigurableApplicationContext server, String method,
        String path, String payload, String token, String key, String etag) throws Exception {
        return sendMutation(http, server, new Mutation(method, path, payload, etag != null, false), token,
            key == null ? List.of() : List.of(key), etag);
    }

    private HttpResponse<String> sendMutation(HttpClient http, ConfigurableApplicationContext server, Mutation mutation,
        String token, List<String> keys, String etag) throws Exception {
        HttpRequest.Builder request = request(server, mutation.path())
            .header("Content-Type", mutation.multipart() ? "multipart/form-data; boundary=release-boundary" : "application/json");
        if (token != null && !token.isEmpty()) request.header("Authorization", "Bearer " + token);
        for (String key : keys) request.header("Idempotency-Key", key);
        if (etag != null && !etag.isEmpty()) request.header("If-Match", etag);
        request.method(mutation.method(), HttpRequest.BodyPublishers.ofString(mutation.payload()));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (token != null && !token.isEmpty()) safe(response, List.of(token, mediaRoot.toString()));
        return response;
    }

    private HttpResponse<String> get(HttpClient http, ConfigurableApplicationContext server, String path, String token) throws Exception {
        HttpRequest.Builder request = request(server, path).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode publicNotice(HttpClient http, ConfigurableApplicationContext server, String id) throws Exception {
        HttpResponse<String> response = get(http, server, "/api/v2/notices", null);
        assertThat(response.statusCode()).isEqualTo(200);
        for (JsonNode item : body(response).at("/data/items")) {
            if (item.path("id").asText().equals(id)) {
                assertThat(item.has("templateId")).isFalse();
                return item;
            }
        }
        throw new AssertionError("Created test notice is missing from public list");
    }

    private HttpRequest.Builder request(ConfigurableApplicationContext server, String path) {
        int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private HttpClient client() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(); }
    private JsonNode body(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private String key() { return "ops-e2e-" + UUID.randomUUID(); }
    private void success(ProcessResult result) { assertThat(result.exitCode()).as("CLI exit status").isZero(); }

    private String notice(String templateId, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "GENERAL");
        payload.put("translations", Map.of("ko", Map.of("title", title, "body", "운영 본문"),
            "en", Map.of("title", title, "body", "Operational body")));
        payload.put("links", List.of());
        payload.put("templateId", templateId);
        return JSON.writeValueAsString(payload);
    }

    private String multipart() {
        // Header rejection precedes decoding; still use a correctly framed, nonempty file part.
        return "--release-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\n"
            + "Content-Type: image/png\r\n\r\nfixture\r\n--release-boundary--\r\n";
    }

    private Map<String, String> state(JdbcTemplate jdbc) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new AssertionError("Unexpected fixture table name");
            result.put(table, digest(jdbc, table));
        }
        return result;
    }

    private String digest(JdbcTemplate jdbc, String table) {
        // Compare row values without printing account values or session hashes on failure.
        return jdbc.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) FROM "
            + table + " t", String.class);
    }

    private long count(JdbcTemplate jdbc, String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private long pendingReservations(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_idempotency_records WHERE state='IN_PROGRESS'", Long.class);
    }

    private String completedDigest(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) "
            + "FROM admin_idempotency_records t WHERE state='COMPLETED'", String.class);
    }

    private void rejectedAfterReservation(JdbcTemplate jdbc, Map<String, String> before, long pending, String completed) throws Exception {
        Map<String, String> expected = new LinkedHashMap<>(before);
        Map<String, String> actual = state(jdbc);
        expected.remove("admin_idempotency_records");
        actual.remove("admin_idempotency_records");
        assertThat(actual).isEqualTo(expected);
        assertThat(pendingReservations(jdbc)).isEqualTo(pending + 1);
        assertThat(completedDigest(jdbc)).isEqualTo(completed);
        try (var files = Files.walk(mediaRoot)) {
            assertThat(files.filter(Files::isRegularFile).count()).isZero();
        }
    }

    private void unchanged(JdbcTemplate jdbc, Map<String, String> before) throws Exception {
        assertThat(state(jdbc)).isEqualTo(before);
        try (var files = Files.walk(mediaRoot)) {
            assertThat(files.filter(Files::isRegularFile).count()).as("rejected uploads leave no files").isZero();
        }
    }

    private void error(HttpResponse<String> response, int status, String code, boolean retryable) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode json = body(response);
        assertThat(json.at("/error/code").asText()).isEqualTo(code);
        assertThat(json.at("/error/retryable").asBoolean()).isEqualTo(retryable);
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(requestId).matches("[0-9a-f-]{36}");
        assertThat(json.at("/meta/requestId").asText()).isEqualTo(requestId);
        assertThat(json.has("data")).isFalse();
    }

    private void safe(HttpResponse<String> response, List<String> secrets) { noSecrets(response.body() + response.headers().map(), secrets); }
    private void noSecrets(String text, List<String> secrets) {
        for (String secret : secrets) assertThat(secret.isEmpty() || !text.contains(secret)).as("no sensitive fixture disclosure").isTrue();
    }
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private record Mutation(String method, String path, String payload, boolean ifMatch, boolean multipart) {}
    private record ProcessResult(int exitCode, String output) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary MutableClock operationalBoundaryClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-09-29T03:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}

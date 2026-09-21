package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.auth.AdminPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Drives the public and administrator notice endpoints against the real KST-scoped notice table. */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class NoticeFlowIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID ADMIN_ID = UUID.fromString("7c9a2b1e-2f9c-4f0a-9b53-2f4c3a0e6d22");
    private static final String ADMIN_LIST_ROUTE = "/api/v2/admin/notices";
    private static final String PLACEHOLDER_TEMPLATE = "template-registration-required";

    private static final String VALID_CREATE_BODY = """
        {
          "type": "GENERAL",
          "translations": {
            "ko": {"title": "관리자 공지", "body": "관리자 본문"},
            "en": {"title": "Admin notice", "body": "Admin body"}
          },
          "links": [],
          "templateId": null
        }
        """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private dev.espero.festival.persistence.NoticeTemplateStore templates;

    @MockitoBean
    private CatalogSnapshotProvider snapshots;

    private MockMvc mvc;
    private int keySequence;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(snapshots.publishedLocales()).thenReturn(List.of("ko", "en", "zh-Hans", "ja"));
        clock.set(OffsetDateTime.parse("2030-10-01T12:00:00+09:00"));
        jdbc.update("DELETE FROM notice_link_translations", Map.of());
        jdbc.update("DELETE FROM notice_links", Map.of());
        jdbc.update("DELETE FROM notice_translations", Map.of());
        jdbc.update("DELETE FROM notices", Map.of());
        jdbc.update("DELETE FROM admin_idempotency_records", Map.of());
        jdbc.update("DELETE FROM admin_audit_events", Map.of());
        jdbc.update("DELETE FROM admin_accounts", Map.of());
        jdbc.update("""
            INSERT INTO admin_accounts (
                id, username, password_hash, authority, enabled, created_at, updated_at, last_login_at
            ) VALUES (
                :id, 'notice-admin', 'test-only-password-hash', 'ADMIN', true,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
            )
            """, new MapSqlParameterSource("id", ADMIN_ID));
    }

    @Test
    void listsTodaysGeneralNoticeAndAnyDayLostFoundOrderedNewestFirst() throws Exception {
        UUID older = insertNotice("GENERAL", "2030-10-01T09:00:00+09:00");
        UUID newer = insertNotice("GENERAL", "2030-10-01T10:00:00+09:00");
        UUID yesterdayGeneral = insertNotice("GENERAL", "2030-09-30T23:00:00+09:00");
        UUID oldLostFound = insertNotice("LOST_FOUND", "2030-09-20T09:00:00+09:00");

        mvc.perform(get("/api/v2/notices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(3)))
            .andExpect(jsonPath("$.data.items[0].id").value(newer.toString()))
            .andExpect(jsonPath("$.data.items[1].id").value(older.toString()))
            .andExpect(jsonPath("$.data.items[2].id").value(oldLostFound.toString()))
            .andExpect(jsonPath("$.data.visibleIds", org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(yesterdayGeneral.toString())
            )));
    }

    @Test
    void returnsOnlyItemsCompleteInTheRequestedPublishedLocale() throws Exception {
        UUID fullyTranslated = insertNotice("GENERAL", "2030-10-01T10:00:00+09:00");
        insertZhTranslation(fullyTranslated);
        insertLink(fullyTranslated, "https://example.invalid/a");
        UUID koOnly = insertKoOnlyNotice("2030-10-01T09:00:00+09:00");

        mvc.perform(get("/api/v2/notices").param("locale", "zh-Hans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(fullyTranslated.toString()))
            .andExpect(jsonPath("$.data.items[0].contentLocale").value("zh-Hans"))
            .andExpect(jsonPath("$.data.items[0].title").value("模拟标题"))
            .andExpect(jsonPath("$.data.items[0].links[0].label").value("链接"))
            .andExpect(jsonPath("$.data.visibleIds", org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(koOnly.toString())
            )));

        mvc.perform(get("/api/v2/notices").param("locale", "en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(fullyTranslated.toString()))
            .andExpect(jsonPath("$.data.items[0].contentLocale").value("en"))
            .andExpect(jsonPath("$.data.items[0].title").value("Title"))
            .andExpect(jsonPath("$.data.items[0].links[0].label").value("Link"));
    }

    @Test
    void hidesLegacyNoticesWithAHostlessHttpsLink() throws Exception {
        UUID valid = insertNotice("GENERAL", "2030-10-01T10:00:00+09:00");
        insertLink(valid, "https://example.invalid/a");
        UUID invalid = insertNotice("GENERAL", "2030-10-01T09:00:00+09:00");
        insertLink(invalid, "https:///legacy-path");

        mvc.perform(get("/api/v2/notices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(valid.toString()))
            .andExpect(jsonPath("$.data.visibleIds", org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(invalid.toString())
            )));
    }

    @Test
    void distinguishesAnUnpublishedKnownLocaleFromUnknownQueries() throws Exception {
        when(snapshots.publishedLocales()).thenReturn(List.of("ko"));

        mvc.perform(get("/api/v2/notices").param("locale", "en"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("LOCALE_NOT_READY"));
        mvc.perform(get("/api/v2/notices").param("locale", "fr"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/v2/notices").param("unexpected", "x"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
    }

    @Test
    void returnsConditionalCachingHeadersAndSupportsRevalidation() throws Exception {
        insertNotice("LOST_FOUND", "2030-09-01T09:00:00+09:00");

        var first = mvc.perform(get("/api/v2/notices")).andExpect(status().isOk()).andReturn().getResponse();
        String etag = first.getHeader("ETag");
        assertThat(etag).matches("\"[0-9a-f]{64}\"");
        assertThat(first.getHeader("Cache-Control")).isEqualTo("private, no-cache, must-revalidate");

        mvc.perform(get("/api/v2/notices").header("If-None-Match", etag))
            .andExpect(status().isNotModified());
    }

    @Test
    void createsReadsUpdatesAndSoftDeletesANoticeThroughTheAdminApi() throws Exception {
        String created = mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(VALID_CREATE_BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.type").value("GENERAL"))
            .andExpect(jsonPath("$.data.translations.ko.title").value("관리자 공지"))
            .andExpect(jsonPath("$.data.templateId").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).path("data").path("id").asString();

        mvc.perform(asAdmin(get(ADMIN_LIST_ROUTE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(id));

        String current = mvc.perform(asAdmin(get(ADMIN_LIST_ROUTE + "/" + id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.translations.en.title").value("Admin notice"))
            .andReturn().getResponse().getHeader("ETag");
        assertThat(current).matches("\"[0-9a-f]{64}\"");

        String updateBody = """
            {
              "type": "LOST_FOUND",
              "translations": {
                "ko": {"title": "수정된 공지", "body": "수정 본문"},
                "en": {"title": "Updated notice", "body": "Updated body"}
              },
              "links": [],
              "templateId": null
            }
            """;
        mvc.perform(asAdmin(put(ADMIN_LIST_ROUTE + "/" + id))
                .header("If-Match", current)
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(updateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("LOST_FOUND"))
            .andExpect(jsonPath("$.data.translations.ko.title").value("수정된 공지"));

        mvc.perform(get("/api/v2/notices"))
            .andExpect(jsonPath("$.data.items[0].title").value("수정된 공지"));

        String beforeDelete = mvc.perform(asAdmin(get(ADMIN_LIST_ROUTE + "/" + id)))
            .andReturn().getResponse().getHeader("ETag");
        mvc.perform(asAdmin(delete(ADMIN_LIST_ROUTE + "/" + id))
                .header("If-Match", beforeDelete)
                .header("Idempotency-Key", nextKey()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deleted").value(true));

        mvc.perform(asAdmin(get(ADMIN_LIST_ROUTE + "/" + id)))
            .andExpect(status().isNotFound());
        mvc.perform(asAdmin(delete(ADMIN_LIST_ROUTE + "/" + id))
                .header("If-Match", beforeDelete)
                .header("Idempotency-Key", nextKey()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ALREADY_DELETED"));
        assertThat(auditCount()).isEqualTo(3);
    }

    @Test
    void createsANoticeWhoseLinkLeavesTheUntranslatedLabelsNull() throws Exception {
        String withLink = """
            {
              "type": "GENERAL",
              "translations": {
                "ko": {"title": "링크 공지", "body": "본문"},
                "en": {"title": "Linked notice", "body": "Body"}
              },
              "links": [{"url": "https://example.invalid/a", "labels": {"ko": "링크", "en": "Link", "zh-Hans": null, "ja": null}}],
              "templateId": null
            }
            """;
        String key = nextKey();

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(withLink))
                .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v2/notices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].links[0].label").value("링크"));
    }

    @Test
    void requiresIdempotencyKeyOnCreateAndIfMatchOnUpdate() throws Exception {
        mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .contentType("application/json")
                .content(VALID_CREATE_BODY))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        UUID id = insertNotice("GENERAL", "2030-10-01T09:00:00+09:00");
        mvc.perform(asAdmin(put(ADMIN_LIST_ROUTE + "/" + id))
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(VALID_CREATE_BODY))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void replaysACompletedIdempotencyKeyAndRejectsReuseWithADifferentBody() throws Exception {
        String key = nextKey();
        String first = mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(VALID_CREATE_BODY))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String replay = mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(VALID_CREATE_BODY))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(countNotices()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);

        String differentBody = VALID_CREATE_BODY.replace("관리자 공지", "다른 공지");
        mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(differentBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsMismatchedIfMatchAsAnEditConflict() throws Exception {
        UUID id = insertNotice("GENERAL", "2030-10-01T09:00:00+09:00");

        mvc.perform(asAdmin(put(ADMIN_LIST_ROUTE + "/" + id))
                .header("If-Match", "\"" + "0".repeat(64) + "\"")
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(VALID_CREATE_BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EDIT_CONFLICT"));
    }

    @Test
    void rejectsInvalidNoticeInputShapes() throws Exception {
        String missingEnglish = """
            {"type":"GENERAL","translations":{"ko":{"title":"제목","body":"본문"}},"links":[],"templateId":null}
            """;
        mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(missingEnglish))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("ENGLISH_REQUIRED"));

        String extraLabel = """
            {
              "type": "GENERAL",
              "translations": {
                "ko": {"title": "제목", "body": "본문"},
                "en": {"title": "Title", "body": "Body"}
              },
              "links": [{"url": "https://example.invalid/a", "labels": {"ko": "링크", "en": "Link", "zh-Hans": "不应该出现", "ja": null}}],
              "templateId": null
            }
            """;
        mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(extraLabel))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("LINK_LABEL_UNEXPECTED"));

        String malformedHttpsUri = """
            {
              "type": "GENERAL",
              "translations": {
                "ko": {"title": "제목", "body": "본문"},
                "en": {"title": "Title", "body": "Body"}
              },
              "links": [{"url": "https://[invalid", "labels": {"ko": "링크", "en": "Link", "zh-Hans": null, "ja": null}}],
              "templateId": null
            }
            """;
        mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(malformedHttpsUri))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        String withTemplate = VALID_CREATE_BODY.replace("\"templateId\": null", "\"templateId\": \"" + UUID.randomUUID() + "\"");
        mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(withTemplate))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("TEMPLATE_NOT_FOUND"));
    }

    @Test
    void servesThePlaceholderTemplateInEveryLocaleToAdministratorsOnly() throws Exception {
        mvc.perform(get("/api/v2/admin/notice-templates"))
            .andExpect(status().isUnauthorized());

        mvc.perform(asAdmin(get("/api/v2/admin/notice-templates")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(PLACEHOLDER_TEMPLATE))
            .andExpect(jsonPath("$.data.items[0].name").value("템플릿 등록 필요"));

        mvc.perform(asAdmin(get("/api/v2/admin/notice-templates/" + PLACEHOLDER_TEMPLATE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.translations.ko.title").value("템플릿 등록 필요"))
            .andExpect(jsonPath("$.data.translations.ko.body").value("템플릿 등록 필요"))
            .andExpect(jsonPath("$.data.translations.en.title").value("Template registration required"))
            .andExpect(jsonPath("$.data.translations['zh-Hans'].title").value("需要登记模板"))
            .andExpect(jsonPath("$.data.translations.ja.title").value("テンプレート登録が必要"));

        mvc.perform(asAdmin(get("/api/v2/admin/notice-templates/no-such-template")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(asAdmin(get("/api/v2/admin/notice-templates").param("page", "2")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
    }

    @Test
    void keepsTheTemplateANoticeStartedFromUntilThatTemplateIsRemoved() throws Exception {
        String fromTemplate = VALID_CREATE_BODY.replace(
            "\"templateId\": null", "\"templateId\": \"" + PLACEHOLDER_TEMPLATE + "\""
        );
        String body = mvc.perform(asAdmin(post(ADMIN_LIST_ROUTE))
                .header("Idempotency-Key", nextKey())
                .contentType("application/json")
                .content(fromTemplate))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.templateId").value(PLACEHOLDER_TEMPLATE))
            .andReturn().getResponse().getContentAsString();
        String noticeId = objectMapper.readTree(body).path("data").path("id").asString();

        dev.espero.festival.domain.NoticeTemplate placeholder = templates.find(PLACEHOLDER_TEMPLATE).orElseThrow();
        try {
            templates.replaceAll(List.of(new dev.espero.festival.domain.NoticeTemplate(
                "rain-delay", "우천 지연",
                Map.of("ko", new dev.espero.festival.domain.NoticeTranslation("우천으로 지연", "공연이 지연됩니다."))
            )), clock.instant());

            mvc.perform(asAdmin(get("/api/v2/admin/notice-templates")))
                .andExpect(jsonPath("$.data.items[*].id", org.hamcrest.Matchers.contains("rain-delay")))
                .andExpect(jsonPath("$.data.items[0].translations.en").doesNotExist());
            mvc.perform(asAdmin(get(ADMIN_LIST_ROUTE + "/" + noticeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translations.ko.title").value("관리자 공지"))
                .andExpect(jsonPath("$.data.templateId").doesNotExist());
        } finally {
            templates.replaceAll(List.of(placeholder), clock.instant());
        }
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(
            new AdminPrincipal(ADMIN_ID, "notice-admin", "ADMIN"),
            null,
            List.of(new SimpleGrantedAuthority("ADMIN"))
        )));
    }

    private String nextKey() {
        return "notice-flow-" + (++keySequence);
    }

    private long auditCount() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE admin_id = :adminId",
            new MapSqlParameterSource("adminId", ADMIN_ID),
            Long.class
        );
        return count == null ? 0 : count;
    }

    private long countNotices() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notices", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private UUID insertNotice(String category, String createdAt) {
        UUID id = UUID.randomUUID();
        Instant createdInstant = OffsetDateTime.parse(createdAt).toInstant();
        jdbc.update("""
            INSERT INTO notices (id, festival_id, category, created_at, updated_at)
            VALUES (:id, :festivalId, :category, :createdAt, :createdAt)
            """, new MapSqlParameterSource()
            .addValue("id", id).addValue("festivalId", FESTIVAL_ID).addValue("category", category)
            .addValue("createdAt", OffsetDateTime.ofInstant(createdInstant, ZoneOffset.UTC)));
        jdbc.update("""
            INSERT INTO notice_translations (notice_id, locale, title, body) VALUES (:id, 'ko', '제목', '본문')
            """, Map.of("id", id));
        jdbc.update("""
            INSERT INTO notice_translations (notice_id, locale, title, body) VALUES (:id, 'en', 'Title', 'Body')
            """, Map.of("id", id));
        return id;
    }

    private UUID insertKoOnlyNotice(String createdAt) {
        UUID id = UUID.randomUUID();
        Instant createdInstant = OffsetDateTime.parse(createdAt).toInstant();
        jdbc.update("""
            INSERT INTO notices (id, festival_id, category, created_at, updated_at)
            VALUES (:id, :festivalId, 'GENERAL', :createdAt, :createdAt)
            """, new MapSqlParameterSource()
            .addValue("id", id).addValue("festivalId", FESTIVAL_ID)
            .addValue("createdAt", OffsetDateTime.ofInstant(createdInstant, ZoneOffset.UTC)));
        jdbc.update("""
            INSERT INTO notice_translations (notice_id, locale, title, body) VALUES (:id, 'ko', '한국어만', '본문')
            """, Map.of("id", id));
        return id;
    }

    private void insertZhTranslation(UUID noticeId) {
        jdbc.update("""
            INSERT INTO notice_translations (notice_id, locale, title, body)
            VALUES (:id, 'zh-Hans', '模拟标题', '模拟正文')
            """, Map.of("id", noticeId));
    }

    private void insertLink(UUID noticeId, String url) {
        UUID linkId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO notice_links (id, notice_id, url, sort_order) VALUES (:linkId, :noticeId, :url, 0)
            """, Map.of("linkId", linkId, "noticeId", noticeId, "url", url));
        jdbc.update("""
            INSERT INTO notice_link_translations (link_id, locale, label) VALUES (:linkId, 'ko', '링크')
            """, Map.of("linkId", linkId));
        jdbc.update("""
            INSERT INTO notice_link_translations (link_id, locale, label) VALUES (:linkId, 'en', 'Link')
            """, Map.of("linkId", linkId));
        jdbc.update("""
            INSERT INTO notice_link_translations (link_id, locale, label) VALUES (:linkId, 'zh-Hans', '链接')
            """, Map.of("linkId", linkId));
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    /** A test clock so "today" (KST) is deterministic instead of the wall clock. */
    static final class MutableClock extends Clock {

        private volatile Instant instant = Instant.EPOCH;

        void set(OffsetDateTime time) {
            instant = time.toInstant();
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

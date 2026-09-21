package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.domain.Notice;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the real V17 notice schema against NoticeStore's read queries. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class NoticeStoreIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final Instant WINDOW_START = Instant.parse("2030-10-01T15:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2030-10-02T15:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private NoticeStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @Transactional
    void findVisibleShowsLostFoundRegardlessOfDateAndGeneralOnlyInsideTheWindow() {
        UUID insideGeneral = insertNotice("GENERAL", WINDOW_START.plusSeconds(3600));
        UUID outsideGeneral = insertNotice("GENERAL", WINDOW_START.minusSeconds(3600));
        UUID oldLostFound = insertNotice("LOST_FOUND", WINDOW_START.minusSeconds(86400));

        List<Notice> visible = store.findVisible(FESTIVAL_ID, WINDOW_START, WINDOW_END);

        assertThat(visible).extracting(Notice::id).contains(insideGeneral, oldLostFound);
        assertThat(visible).noneMatch(notice -> notice.id().equals(outsideGeneral));
    }

    @Test
    @Transactional
    void findVisibleAssemblesTranslationsAndOrderedLinksWithLabels() {
        UUID noticeId = insertNotice("GENERAL", WINDOW_START.plusSeconds(60));
        insertLink(noticeId, "https://example.invalid/a", 0, Map.of("ko", "링크1", "en", "Link1"));
        insertLink(noticeId, "https://example.invalid/b", 1, Map.of("ko", "링크2", "en", "Link2"));

        List<Notice> visible = store.findVisible(FESTIVAL_ID, WINDOW_START, WINDOW_END);
        Notice notice = visible.stream().filter(n -> n.id().equals(noticeId)).findFirst().orElseThrow();

        assertThat(notice.translations()).containsOnlyKeys("ko", "en");
        assertThat(notice.translations().get("ko").title()).isEqualTo("제목");
        assertThat(notice.links()).extracting(link -> link.url())
            .containsExactly("https://example.invalid/a", "https://example.invalid/b");
        assertThat(notice.links().get(0).labels().get("ko")).isEqualTo("링크1");
        assertThat(notice.links().get(0).labels()).doesNotContainKey("zh-Hans");
    }

    @Test
    @Transactional
    void findVisibleExcludesSoftDeletedNotices() {
        UUID deleted = insertNotice("LOST_FOUND", WINDOW_START);
        jdbc.update("UPDATE notices SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id", Map.of("id", deleted));

        List<Notice> visible = store.findVisible(FESTIVAL_ID, WINDOW_START, WINDOW_END);

        assertThat(visible).noneMatch(notice -> notice.id().equals(deleted));
    }

    @Test
    @Transactional
    void findAllForAdminReturnsEveryNonDeletedNoticeRegardlessOfDate() {
        UUID recent = insertNotice("GENERAL", WINDOW_START);
        UUID old = insertNotice("GENERAL", WINDOW_START.minusSeconds(1_000_000));

        List<Notice> all = store.findAllForAdmin(FESTIVAL_ID);

        assertThat(all).extracting(Notice::id).contains(recent, old);
    }

    @Test
    @Transactional
    void findForAdminReturnsEmptyForAnotherFestivalOrDeletedNotice() {
        UUID noticeId = insertNotice("GENERAL", WINDOW_START);

        assertThat(store.findForAdmin(FESTIVAL_ID, noticeId)).isPresent();
        assertThat(store.findForAdmin(UUID.randomUUID(), noticeId)).isEmpty();

        jdbc.update("UPDATE notices SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id", Map.of("id", noticeId));
        assertThat(store.findForAdmin(FESTIVAL_ID, noticeId)).isEmpty();
    }

    private UUID insertNotice(String category, Instant createdAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime timestamp = createdAt.atOffset(ZoneOffset.UTC);
        jdbc.update("""
            INSERT INTO notices (id, festival_id, category, created_at, updated_at)
            VALUES (:id, :festivalId, :category, :createdAt, :createdAt)
            """, Map.of("id", id, "festivalId", FESTIVAL_ID, "category", category, "createdAt", timestamp));
        jdbc.update("""
            INSERT INTO notice_translations (notice_id, locale, title, body)
            VALUES (:noticeId, 'ko', '제목', '본문')
            """, Map.of("noticeId", id));
        jdbc.update("""
            INSERT INTO notice_translations (notice_id, locale, title, body)
            VALUES (:noticeId, 'en', 'Title', 'Body')
            """, Map.of("noticeId", id));
        return id;
    }

    private void insertLink(UUID noticeId, String url, int sortOrder, Map<String, String> labels) {
        UUID linkId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO notice_links (id, notice_id, url, sort_order)
            VALUES (:id, :noticeId, :url, :sortOrder)
            """, Map.of("id", linkId, "noticeId", noticeId, "url", url, "sortOrder", sortOrder));
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            jdbc.update("""
                INSERT INTO notice_link_translations (link_id, locale, label)
                VALUES (:linkId, :locale, :label)
                """, Map.of("linkId", linkId, "locale", entry.getKey(), "label", entry.getValue()));
        }
    }
}

package dev.espero.festival.persistence;

import dev.espero.festival.domain.Notice;
import dev.espero.festival.domain.NoticeCategory;
import dev.espero.festival.domain.NoticeLink;
import dev.espero.festival.domain.NoticeLinkDraft;
import dev.espero.festival.domain.NoticeTranslation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL access for festival_id-scoped notice content. */
@Repository
@Profile("db")
public class NoticeStore {

    private final NamedParameterJdbcTemplate jdbc;

    public NoticeStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Notices visible to the public: LOST_FOUND always, GENERAL only within
     * the given window (the caller's KST "today"). */
    public List<Notice> findVisible(UUID festivalId, Instant generalWindowStart, Instant generalWindowEnd) {
        List<NoticeHeader> headers = jdbc.query("""
            SELECT id, festival_id, category, created_at, updated_at
            FROM notices
            WHERE festival_id = :festivalId
              AND deleted_at IS NULL
              AND (
                category = 'LOST_FOUND'
                OR (created_at >= :windowStart AND created_at < :windowEnd)
              )
            ORDER BY created_at DESC, id
            """,
            new MapSqlParameterSource()
                .addValue("festivalId", festivalId)
                .addValue("windowStart", atUtc(generalWindowStart))
                .addValue("windowEnd", atUtc(generalWindowEnd)),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers);
    }

    /** All non-deleted notices for a festival, most recently updated first. */
    public List<Notice> findAllForAdmin(UUID festivalId) {
        List<NoticeHeader> headers = jdbc.query("""
            SELECT id, festival_id, category, created_at, updated_at
            FROM notices
            WHERE festival_id = :festivalId
              AND deleted_at IS NULL
            ORDER BY updated_at DESC, id
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers);
    }

    public Optional<Notice> findForAdmin(UUID festivalId, UUID noticeId) {
        List<NoticeHeader> headers = jdbc.query("""
            SELECT id, festival_id, category, created_at, updated_at
            FROM notices
            WHERE festival_id = :festivalId
              AND id = :id
              AND deleted_at IS NULL
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId).addValue("id", noticeId),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers).stream().findFirst();
    }

    /** Empty when the id never existed for this festival; otherwise whether it is soft-deleted. */
    public Optional<Boolean> deletionStatus(UUID festivalId, UUID noticeId) {
        return jdbc.query("""
            SELECT deleted_at IS NOT NULL AS deleted
            FROM notices
            WHERE festival_id = :festivalId AND id = :id
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId).addValue("id", noticeId),
            (resultSet, rowNumber) -> resultSet.getBoolean("deleted")
        ).stream().findFirst();
    }

    /** Locks the notice row before an administrator checks its ETag and writes it. */
    public Optional<Notice> findForUpdate(UUID festivalId, UUID noticeId) {
        List<NoticeHeader> headers = jdbc.query("""
            SELECT id, festival_id, category, created_at, updated_at
            FROM notices
            WHERE festival_id = :festivalId
              AND id = :id
              AND deleted_at IS NULL
            FOR UPDATE
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId).addValue("id", noticeId),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers).stream().findFirst();
    }

    @Transactional
    public UUID insert(
        UUID festivalId,
        NoticeCategory category,
        Map<String, NoticeTranslation> translations,
        List<NoticeLinkDraft> links,
        Instant now
    ) {
        UUID noticeId = UUID.randomUUID();
        OffsetDateTime timestamp = atUtc(now);
        jdbc.update("""
            INSERT INTO notices (id, festival_id, category, created_at, updated_at)
            VALUES (:id, :festivalId, :category, :timestamp, :timestamp)
            """,
            new MapSqlParameterSource()
                .addValue("id", noticeId)
                .addValue("festivalId", festivalId)
                .addValue("category", category.name())
                .addValue("timestamp", timestamp)
        );
        writeTranslations(noticeId, translations);
        writeLinks(noticeId, links);
        return noticeId;
    }

    @Transactional
    public void update(
        UUID noticeId,
        NoticeCategory category,
        Map<String, NoticeTranslation> translations,
        List<NoticeLinkDraft> links,
        Instant now
    ) {
        jdbc.update("""
            UPDATE notices
            SET category = :category, updated_at = :updatedAt
            WHERE id = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", noticeId)
                .addValue("category", category.name())
                .addValue("updatedAt", atUtc(now))
        );
        jdbc.update("DELETE FROM notice_translations WHERE notice_id = :id", Map.of("id", noticeId));
        jdbc.update("DELETE FROM notice_links WHERE notice_id = :id", Map.of("id", noticeId));
        writeTranslations(noticeId, translations);
        writeLinks(noticeId, links);
    }

    public void softDelete(UUID noticeId, Instant now) {
        jdbc.update("""
            UPDATE notices
            SET deleted_at = :deletedAt, updated_at = :deletedAt
            WHERE id = :id
            """,
            new MapSqlParameterSource().addValue("id", noticeId).addValue("deletedAt", atUtc(now))
        );
    }

    private void writeTranslations(UUID noticeId, Map<String, NoticeTranslation> translations) {
        for (Map.Entry<String, NoticeTranslation> entry : translations.entrySet()) {
            jdbc.update("""
                INSERT INTO notice_translations (notice_id, locale, title, body)
                VALUES (:noticeId, :locale, :title, :body)
                """,
                new MapSqlParameterSource()
                    .addValue("noticeId", noticeId)
                    .addValue("locale", entry.getKey())
                    .addValue("title", entry.getValue().title())
                    .addValue("body", entry.getValue().body())
            );
        }
    }

    private void writeLinks(UUID noticeId, List<NoticeLinkDraft> links) {
        int sortOrder = 0;
        for (NoticeLinkDraft link : links) {
            UUID linkId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO notice_links (id, notice_id, url, sort_order)
                VALUES (:id, :noticeId, :url, :sortOrder)
                """,
                new MapSqlParameterSource()
                    .addValue("id", linkId)
                    .addValue("noticeId", noticeId)
                    .addValue("url", link.url())
                    .addValue("sortOrder", sortOrder++)
            );
            for (Map.Entry<String, String> label : link.labels().entrySet()) {
                if (label.getValue() == null) {
                    continue;
                }
                jdbc.update("""
                    INSERT INTO notice_link_translations (link_id, locale, label)
                    VALUES (:linkId, :locale, :label)
                    """,
                    new MapSqlParameterSource()
                        .addValue("linkId", linkId)
                        .addValue("locale", label.getKey())
                        .addValue("label", label.getValue())
                );
            }
        }
    }

    private List<Notice> hydrate(List<NoticeHeader> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = headers.stream().map(NoticeHeader::id).toList();
        Map<UUID, Map<String, NoticeTranslation>> translationsByNotice = loadTranslations(ids);
        Map<UUID, List<NoticeLink>> linksByNotice = loadLinks(ids);
        List<Notice> result = new ArrayList<>(headers.size());
        for (NoticeHeader header : headers) {
            result.add(new Notice(
                header.id(),
                header.festivalId(),
                header.category(),
                translationsByNotice.getOrDefault(header.id(), Map.of()),
                linksByNotice.getOrDefault(header.id(), List.of()),
                header.createdAt(),
                header.updatedAt()
            ));
        }
        return result;
    }

    private Map<UUID, Map<String, NoticeTranslation>> loadTranslations(List<UUID> noticeIds) {
        Map<UUID, Map<String, NoticeTranslation>> result = new LinkedHashMap<>();
        jdbc.query("""
            SELECT notice_id, locale, title, body
            FROM notice_translations
            WHERE notice_id IN (:noticeIds)
            """,
            new MapSqlParameterSource("noticeIds", noticeIds),
            (resultSet, rowNumber) -> {
                UUID noticeId = resultSet.getObject("notice_id", UUID.class);
                result.computeIfAbsent(noticeId, key -> new LinkedHashMap<>())
                    .put(resultSet.getString("locale"), new NoticeTranslation(
                        resultSet.getString("title"),
                        resultSet.getString("body")
                    ));
                return null;
            }
        );
        return result;
    }

    private Map<UUID, List<NoticeLink>> loadLinks(List<UUID> noticeIds) {
        List<LinkHeader> linkHeaders = jdbc.query("""
            SELECT id, notice_id, url
            FROM notice_links
            WHERE notice_id IN (:noticeIds)
            ORDER BY sort_order
            """,
            new MapSqlParameterSource("noticeIds", noticeIds),
            (resultSet, rowNumber) -> new LinkHeader(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("notice_id", UUID.class),
                resultSet.getString("url")
            )
        );
        if (linkHeaders.isEmpty()) {
            return Map.of();
        }
        List<UUID> linkIds = linkHeaders.stream().map(LinkHeader::id).toList();
        Map<UUID, Map<String, String>> labelsByLink = new LinkedHashMap<>();
        jdbc.query("""
            SELECT link_id, locale, label
            FROM notice_link_translations
            WHERE link_id IN (:linkIds)
            """,
            new MapSqlParameterSource("linkIds", linkIds),
            (resultSet, rowNumber) -> {
                UUID linkId = resultSet.getObject("link_id", UUID.class);
                labelsByLink.computeIfAbsent(linkId, key -> new LinkedHashMap<>())
                    .put(resultSet.getString("locale"), resultSet.getString("label"));
                return null;
            }
        );
        Map<UUID, List<NoticeLink>> linksByNotice = new LinkedHashMap<>();
        for (LinkHeader header : linkHeaders) {
            linksByNotice.computeIfAbsent(header.noticeId(), key -> new ArrayList<>())
                .add(new NoticeLink(header.id(), header.url(), labelsByLink.getOrDefault(header.id(), Map.of())));
        }
        return linksByNotice;
    }

    private NoticeHeader mapHeader(ResultSet resultSet) throws SQLException {
        return new NoticeHeader(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("festival_id", UUID.class),
            NoticeCategory.valueOf(resultSet.getString("category")),
            resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record NoticeHeader(
        UUID id,
        UUID festivalId,
        NoticeCategory category,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private record LinkHeader(UUID id, UUID noticeId, String url) {}
}

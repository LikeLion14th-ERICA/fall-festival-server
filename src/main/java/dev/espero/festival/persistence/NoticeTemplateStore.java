package dev.espero.festival.persistence;

import dev.espero.festival.domain.NoticeTemplate;
import dev.espero.festival.domain.NoticeTranslation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL access for notice templates. The API reads them; only the CLI replaces them. */
@Repository
@Profile("db")
public class NoticeTemplateStore {

    private final NamedParameterJdbcTemplate jdbc;

    public NoticeTemplateStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All templates in their display order. */
    public List<NoticeTemplate> findAll() {
        List<String[]> headers = jdbc.query("""
            SELECT id, name FROM notice_templates ORDER BY sort_order, id
            """, new MapSqlParameterSource(),
            (resultSet, rowNumber) -> new String[] {resultSet.getString("id"), resultSet.getString("name")});
        Map<String, Map<String, NoticeTranslation>> translations = loadTranslations(null);
        List<NoticeTemplate> templates = new ArrayList<>(headers.size());
        for (String[] header : headers) {
            templates.add(new NoticeTemplate(header[0], header[1], translations.getOrDefault(header[0], Map.of())));
        }
        return templates;
    }

    public Optional<NoticeTemplate> find(String id) {
        List<String> names = jdbc.query("""
            SELECT name FROM notice_templates WHERE id = :id
            """, new MapSqlParameterSource("id", id), (resultSet, rowNumber) -> resultSet.getString("name"));
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new NoticeTemplate(id, names.getFirst(), loadTranslations(id).getOrDefault(id, Map.of())));
    }

    public boolean exists(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM notice_templates WHERE id = :id)
            """, new MapSqlParameterSource("id", id), Boolean.class));
    }

    /**
     * Replaces every template with {@code templates}, in list order. Notices
     * that started from a removed template keep their text and lose only the
     * reference.
     */
    @Transactional
    public void replaceAll(List<NoticeTemplate> templates, Instant now) {
        jdbc.update("DELETE FROM notice_templates WHERE id NOT IN (:ids)", new MapSqlParameterSource(
            "ids", templates.isEmpty() ? List.of("") : templates.stream().map(NoticeTemplate::id).toList()
        ));
        jdbc.update("DELETE FROM notice_template_translations", new MapSqlParameterSource());
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        int sortOrder = 1;
        for (NoticeTemplate template : templates) {
            jdbc.update("""
                INSERT INTO notice_templates (id, name, sort_order, updated_at)
                VALUES (:id, :name, :sortOrder, :updatedAt)
                ON CONFLICT (id) DO UPDATE
                SET name = EXCLUDED.name, sort_order = EXCLUDED.sort_order, updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("id", template.id())
                .addValue("name", template.name())
                .addValue("sortOrder", sortOrder++)
                .addValue("updatedAt", timestamp));
            for (Map.Entry<String, NoticeTranslation> translation : template.translations().entrySet()) {
                jdbc.update("""
                    INSERT INTO notice_template_translations (template_id, locale, title, body)
                    VALUES (:templateId, :locale, :title, :body)
                    """, new MapSqlParameterSource()
                    .addValue("templateId", template.id())
                    .addValue("locale", translation.getKey())
                    .addValue("title", translation.getValue().title())
                    .addValue("body", translation.getValue().body()));
            }
        }
    }

    private Map<String, Map<String, NoticeTranslation>> loadTranslations(String id) {
        Map<String, Map<String, NoticeTranslation>> result = new LinkedHashMap<>();
        jdbc.query("""
            SELECT template_id, locale, title, body
            FROM notice_template_translations
            WHERE CAST(:id AS TEXT) IS NULL OR template_id = :id
            """, new MapSqlParameterSource("id", id), resultSet -> {
            result.computeIfAbsent(resultSet.getString("template_id"), key -> new LinkedHashMap<>())
                .put(resultSet.getString("locale"), new NoticeTranslation(
                    resultSet.getString("title"), resultSet.getString("body")
                ));
        });
        return result;
    }
}

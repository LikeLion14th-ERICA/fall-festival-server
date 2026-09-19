package dev.espero.festival.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Checks that a revision's content is complete in one non-Korean locale. The
 * snapshot load already rejects a missing row it joins to, but it cannot see
 * a missing list item, a dropped optional field or performance content that
 * is read per request. This compares every translated row with its Korean
 * counterpart instead, so a locale is published only when nothing in it
 * would be empty where Korean has text.
 */
@Repository
@Profile("db")
public class LocaleCompletenessStore {

    /**
     * A per-locale table compared with its Korean rows. {@code keys} identify
     * the same item across locales and {@code optional} columns must be
     * present in both or in neither. {@code exact} tables are lists, where an
     * extra row in the locale would show an item Korean does not have.
     */
    private record Check(String table, List<String> keys, List<String> optional, boolean exact) {}

    private static final List<Check> CHECKS = List.of(
        new Check("space_translations", List.of("space_id"), List.of(
            "operator_text", "hours_text", "description_text", "experience_text", "contact_label", "contact_url"
        ), false),
        new Check("space_sort_orders", List.of("space_id"), List.of(), true),
        new Check("space_events", List.of("space_id", "sort_order"), List.of(), true),
        new Check("space_menu_items", List.of("space_id", "sort_order", "price_amount"), List.of(), true),
        new Check("place_translations", List.of("place_id"), List.of(
            "name", "location_text", "hours_text", "description_text", "usage_text"
        ), false),
        new Check("map_translations", List.of("map_id"), List.of(), false),
        new Check("map_pin_translations", List.of("map_id", "map_version", "pin_id"), List.of(), false),
        new Check("map_pin_filter_group_translations", List.of("filter_group"), List.of(), false),
        new Check("artist_translations", List.of("artist_id"), List.of("introduction"), false),
        new Check("artist_link_translations", List.of("artist_id", "sort_order"), List.of(), false),
        new Check("artist_song_translations", List.of("artist_id", "sort_order"), List.of(), false),
        new Check("performance_translations", List.of("performance_id"), List.of("description"), false),
        new Check("prohibited_item_translations", List.of("item_id"), List.of(), false),
        new Check("prohibited_messages", List.of(), List.of(), false),
        new Check("festival_link_translations", List.of("link_id"), List.of(), false)
    );

    private final NamedParameterJdbcTemplate jdbc;

    public LocaleCompletenessStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns what is missing in {@code locale}; empty means the locale is complete. */
    public List<String> findings(UUID revisionId, String locale) {
        List<String> findings = new ArrayList<>();
        if (CatalogSnapshotStore.KOREAN.equals(locale)) {
            return findings;
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("locale", locale);
        for (Check check : CHECKS) {
            long missing = count(missingSql(check, "'ko'", ":locale"), parameters);
            if (missing > 0) {
                findings.add(check.table() + ": " + missing + " Korean row(s) have no matching " + locale + " row");
            }
            if (check.exact()) {
                long extra = count(missingSql(check, ":locale", "'ko'"), parameters);
                if (extra > 0) {
                    findings.add(check.table() + ": " + extra + " " + locale + " row(s) have no Korean row");
                }
            }
        }
        addBaseRowFindings(findings, parameters, locale);
        return findings;
    }

    /**
     * Rows of {@code fromLocale} without a counterpart in {@code toLocale}.
     * Table and column names come from the constant CHECKS only.
     */
    private static String missingSql(Check check, String fromLocale, String toLocale) {
        String match = check.keys().stream()
            .map(key -> "other." + key + " = source." + key)
            .collect(Collectors.joining(" AND "));
        String shape = check.optional().stream()
            .map(column -> "(other." + column + " IS NULL) = (source." + column + " IS NULL)")
            .collect(Collectors.joining(" AND "));
        StringBuilder sql = new StringBuilder()
            .append("SELECT count(*) FROM ").append(check.table()).append(" source")
            .append(" WHERE source.festival_revision_id = :revisionId AND source.locale = ").append(fromLocale)
            .append(" AND NOT EXISTS (SELECT 1 FROM ").append(check.table()).append(" other")
            .append(" WHERE other.festival_revision_id = source.festival_revision_id")
            .append(" AND other.locale = ").append(toLocale);
        if (!match.isEmpty()) {
            sql.append(" AND ").append(match);
        }
        if (!shape.isEmpty()) {
            sql.append(" AND ").append(shape);
        }
        return sql.append(")").toString();
    }

    /** Parts whose Korean text lives in a base row rather than a translation row. */
    private void addBaseRowFindings(List<String> findings, MapSqlParameterSource parameters, String locale) {
        if (count("""
            SELECT count(*) FROM festival_revisions r
            WHERE r.id = :revisionId
              AND NOT EXISTS (
                  SELECT 1 FROM festival_title_translations t
                  WHERE t.festival_revision_id = r.id AND t.locale = :locale
              )
            """, parameters) > 0) {
            findings.add("festival_title_translations: festival title has no " + locale + " row");
        }
        long maps = count("""
            SELECT count(*) FROM maps m
            JOIN map_asset_versions a
              ON a.festival_revision_id = m.festival_revision_id
             AND a.map_id = m.id AND a.version = m.current_version
            WHERE m.festival_revision_id = :revisionId
              AND NOT EXISTS (
                  SELECT 1 FROM map_asset_translations t
                  WHERE t.festival_revision_id = a.festival_revision_id
                    AND t.map_id = a.map_id AND t.version = a.version AND t.locale = :locale
              )
            """, parameters);
        if (maps > 0) {
            findings.add("map_asset_translations: " + maps + " current map image(s) have no " + locale + " alt text");
        }
        if (count("""
            SELECT count(*) FROM ticket_guide_revisions g
            WHERE g.festival_revision_id = :revisionId
              AND NOT EXISTS (
                  SELECT 1 FROM ticket_guide_translations t
                  WHERE t.festival_revision_id = g.festival_revision_id AND t.id = g.id
                    AND t.locale = :locale
                    AND cardinality(t.instructions) = cardinality(g.instructions)
              )
            """, parameters) > 0) {
            findings.add("ticket_guide_translations: ticket guide has no complete " + locale + " row");
        }
        if (count("""
            SELECT count(*) FROM stamp_guide_revisions g
            WHERE g.festival_revision_id = :revisionId
              AND NOT EXISTS (
                  SELECT 1 FROM stamp_guide_translations t
                  WHERE t.festival_revision_id = g.festival_revision_id AND t.id = g.id
                    AND t.locale = :locale
                    AND cardinality(t.instructions) = cardinality(g.instructions)
                    AND (t.reward_location_text IS NULL) = (g.reward_location_text IS NULL)
                    AND (t.reward_hours_text IS NULL) = (g.reward_hours_text IS NULL)
              )
            """, parameters) > 0) {
            findings.add("stamp_guide_translations: stamp guide has no complete " + locale + " row");
        }
    }

    private long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0 : value;
    }
}

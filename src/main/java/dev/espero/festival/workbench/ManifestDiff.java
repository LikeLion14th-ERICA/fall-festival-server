package dev.espero.festival.workbench;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Semantic diff between two catalog manifests.
 *
 * <p>Each list section is compared by row identity rather than position, so
 * reordering rows is not a change. A row's identity is its {@code id} when it
 * has one, otherwise its key fields ({@code *Id}, locale, sort order,
 * version, date and group fields). The festival and baseline pointers are
 * reported by the baseline check instead.</p>
 */
final class ManifestDiff {

    private static final Set<String> IGNORED = Set.of("festivalId", "baselineRevisionId");
    private static final Set<String> KEY_FIELDS = Set.of(
        "locale", "sortOrder", "version", "mapVersion", "festivalDate", "filterGroup", "role"
    );

    private ManifestDiff() {}

    static List<Section> diff(JsonNode before, JsonNode after) {
        Set<String> names = new TreeSet<>();
        before.propertyNames().forEach(names::add);
        after.propertyNames().forEach(names::add);
        List<Section> sections = new ArrayList<>();
        for (String name : names) {
            if (IGNORED.contains(name)) {
                continue;
            }
            JsonNode left = before.path(name);
            JsonNode right = after.path(name);
            if (left.isArray() || right.isArray()) {
                Section section = listSection(name, left, right);
                if (section.hasChanges()) {
                    sections.add(section);
                }
            } else if (!left.equals(right)) {
                sections.add(new Section(name, List.of(), List.of(), List.of(name)));
            }
        }
        return sections;
    }

    private static Section listSection(String name, JsonNode left, JsonNode right) {
        Map<String, JsonNode> before = keyed(left);
        Map<String, JsonNode> after = keyed(right);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : after.entrySet()) {
            JsonNode previous = before.get(entry.getKey());
            if (previous == null) {
                added.add(entry.getKey());
            } else if (!previous.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                removed.add(key);
            }
        }
        return new Section(name, added, removed, changed);
    }

    private static Map<String, JsonNode> keyed(JsonNode rows) {
        Map<String, JsonNode> keyed = new LinkedHashMap<>();
        if (rows == null || !rows.isArray()) {
            return keyed;
        }
        for (JsonNode row : rows) {
            String key = identity(row);
            String unique = key;
            for (int copy = 2; keyed.containsKey(unique); copy++) {
                unique = key + "#" + copy;
            }
            keyed.put(unique, row);
        }
        return keyed;
    }

    private static String identity(JsonNode row) {
        if (!row.isObject()) {
            return row.toString();
        }
        if (row.hasNonNull("id")) {
            return row.get("id").asString();
        }
        List<String> parts = new ArrayList<>();
        Iterator<String> fields = new TreeSet<>(row.propertyNames()).iterator();
        while (fields.hasNext()) {
            String field = fields.next();
            if ((field.endsWith("Id") || KEY_FIELDS.contains(field)) && !row.get(field).isNull()) {
                parts.add(field + "=" + row.get(field).asString());
            }
        }
        return parts.isEmpty() ? row.toString() : String.join(",", parts);
    }

    record Section(String name, List<String> added, List<String> removed, List<String> changed) {

        boolean hasChanges() {
            return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
        }
    }
}

package dev.espero.festival.preflight;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded metadata/SELECT inspection in one transaction that is always rolled back. */
final class DatabasePreflight {
    private static final int MAX_ROWS = 500;
    private static final Set<String> NON_CATALOG_TABLES = Set.of(
        "flyway_schema_history", "admin_accounts", "admin_refresh_sessions", "admin_audit_events",
        "catalog_revision_audit", "crowding_state", "festivals", "festival_revisions");
    private final MigrationInventory inventory;

    DatabasePreflight(MigrationInventory inventory) {
        this.inventory = inventory;
    }

    Report inspect(Connection connection, String schema, UUID requestedFestival) throws SQLException {
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        List<String> findings = new ArrayList<>();
        Map<String, String> facts = new LinkedHashMap<>();
        List<Map<String, String>> schemas = new ArrayList<>();
        List<Map<String, String>> relations = new ArrayList<>();
        List<Map<String, String>> migrations = new ArrayList<>();
        List<Map<String, String>> festivals = new ArrayList<>();
        List<Map<String, String>> revisions = new ArrayList<>();
        Map<String, Boolean> catalogPresence = new LinkedHashMap<>();
        try {
            query(connection, """
                SELECT current_setting('server_version') AS server_version,
                       current_setting('server_version_num') AS server_version_num,
                       current_database() AS database_name, current_user AS current_role,
                       session_user AS session_role,
                       current_setting('transaction_read_only') AS read_only,
                       current_setting('transaction_isolation') AS isolation
                """, rows -> {
                    facts.put("postgresqlVersion", rows.getString("server_version"));
                    facts.put("database", rows.getString("database_name"));
                    facts.put("currentUser", rows.getString("current_role"));
                    facts.put("sessionUser", rows.getString("session_role"));
                    facts.put("transactionReadOnly", rows.getString("read_only"));
                    facts.put("transactionIsolation", rows.getString("isolation"));
                    if (!"on".equals(rows.getString("read_only"))
                            || !"repeatable read".equals(rows.getString("isolation"))) {
                        throw new SQLException("Read-only transaction could not be established.");
                    }
                    // The repository's PostgreSQL integration matrix currently proves major version 16 only.
                    if (rows.getInt("server_version_num") / 10000 != 16) {
                        findings.add("POSTGRESQL_VERSION_REQUIRES_REVIEW");
                    }
                });
            facts.put("schema", schema);
            facts.put("requestedFestivalId", requestedFestival == null ? "unset" : requestedFestival.toString());
            query(connection, """
                SELECT n.nspname AS schema_name, pg_get_userbyid(n.nspowner) AS owner_name,
                       has_schema_privilege(n.oid, 'USAGE') AS accessible
                FROM pg_catalog.pg_namespace n
                WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
                ORDER BY n.nspname LIMIT 501
                """, rows -> schemas.add(row(rows, "schema_name", "owner_name", "accessible")));
            query(connection, """
                SELECT n.nspname AS schema_name, c.relname AS relation_name, c.relkind AS relation_kind,
                       has_table_privilege(c.oid, 'SELECT') AS readable
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                ORDER BY n.nspname, c.relname LIMIT 501
                """, rows -> relations.add(row(rows, "schema_name", "relation_name", "relation_kind", "readable")));
            if (schemas.size() > MAX_ROWS || relations.size() > MAX_ROWS) {
                findings.add("METADATA_TRUNCATED");
            }
            if (schemas.stream().noneMatch(row -> schema.equals(row.get("schema_name"))
                    && "t".equals(row.get("accessible")))) {
                findings.add("SCHEMA_MISSING_OR_INACCESSIBLE");
            }
            if (schemas.stream().anyMatch(row -> !schema.equals(row.get("schema_name")))) {
                findings.add("OTHER_USER_SCHEMAS_PRESENT");
            }
            Set<String> localTables = new LinkedHashSet<>();
            Set<String> knownTables = new LinkedHashSet<>(inventory.tablesThrough(Integer.MAX_VALUE));
            knownTables.add("flyway_schema_history");
            for (Map<String, String> relation : relations) {
                if (!schema.equals(relation.get("schema_name"))) {
                    findings.add("OTHER_SCHEMA_RELATIONS_PRESENT");
                    continue;
                }
                String name = relation.get("relation_name");
                if (!knownTables.contains(name) || !Set.of("r", "p").contains(relation.get("relation_kind"))) {
                    findings.add("UNKNOWN_SCHEMA_RELATIONS");
                } else if (!"t".equals(relation.get("readable"))) {
                    findings.add("TABLE_ACCESS_INCOMPLETE");
                } else {
                    localTables.add(name);
                }
            }
            query(connection, """
                SELECT count(*) AS other_sessions,
                       count(*) FILTER (WHERE state IS NULL) AS hidden_sessions
                FROM pg_catalog.pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                  AND backend_type = 'client backend'
                """, rows -> {
                    facts.put("otherClientSessions", rows.getString("other_sessions"));
                    facts.put("sessionsWithHiddenState", rows.getString("hidden_sessions"));
                    if (rows.getLong("other_sessions") > 0) {
                        findings.add("OTHER_DATABASE_SESSIONS_PRESENT");
                    }
                    if (rows.getLong("hidden_sessions") > 0) {
                        findings.add("SESSION_VISIBILITY_INCOMPLETE");
                    }
                });
            inspectMigrations(connection, schema, localTables, migrations, findings);
            if (localTables.contains("festivals")) {
                query(connection, "SELECT id, timezone FROM " + qualified(schema, "festivals") + " ORDER BY id LIMIT 501",
                    rows -> festivals.add(row(rows, "id", "timezone")));
            }
            if (localTables.contains("festival_revisions")) {
                query(connection, "SELECT id, festival_id, revision_number, state FROM "
                    + qualified(schema, "festival_revisions") + " ORDER BY festival_id, revision_number LIMIT 501",
                    rows -> revisions.add(row(rows, "id", "festival_id", "revision_number", "state")));
            }
            if (festivals.size() > MAX_ROWS || revisions.size() > MAX_ROWS) {
                findings.add("FESTIVAL_METADATA_TRUNCATED");
            }
            if (requestedFestival != null && festivals.stream()
                    .noneMatch(row -> requestedFestival.toString().equals(row.get("id")))) {
                findings.add("REQUESTED_FESTIVAL_NOT_FOUND");
            }
            if (requestedFestival == null && !festivals.isEmpty()) {
                findings.add("FESTIVAL_SELECTION_REQUIRED");
            }
            Map<String, Integer> publishedCounts = new LinkedHashMap<>();
            for (Map<String, String> revision : revisions) {
                if ("published".equals(revision.get("state"))) {
                    publishedCounts.merge(revision.get("festival_id"), 1, Integer::sum);
                }
            }
            if (publishedCounts.values().stream().anyMatch(count -> count > 1)) {
                findings.add("AMBIGUOUS_PUBLISHED_REVISION");
            }
            for (String table : localTables.stream().sorted().toList()) {
                if (!NON_CATALOG_TABLES.contains(table)) {
                    query(connection, "SELECT EXISTS (SELECT 1 FROM " + qualified(schema, table)
                        + " LIMIT 1) AS present", rows -> catalogPresence.put(table, rows.getBoolean("present")));
                }
            }
            if (!festivals.isEmpty() || !revisions.isEmpty() || catalogPresence.containsValue(true)) {
                findings.add("EXISTING_FESTIVAL_OR_CATALOG_DATA");
            }
            return new Report(facts, schemas, relations, migrations, festivals, revisions, catalogPresence,
                findings.stream().distinct().toList());
        } finally {
            connection.rollback();
        }
    }

    private void inspectMigrations(Connection connection, String schema, Set<String> tables,
                                   List<Map<String, String>> history, List<String> findings) throws SQLException {
        if (!tables.contains("flyway_schema_history")) {
            if (!tables.isEmpty()) {
                findings.add("FLYWAY_HISTORY_MISSING");
            }
            return;
        }
        query(connection, "SELECT installed_rank, version, type, script, checksum, success FROM "
            + qualified(schema, "flyway_schema_history") + " ORDER BY installed_rank LIMIT 501",
            rows -> history.add(row(rows, "installed_rank", "version", "type", "script", "checksum", "success")));
        if (history.size() > MAX_ROWS) {
            findings.add("FLYWAY_HISTORY_TRUNCATED");
        }
        Map<Integer, MigrationInventory.Migration> known = inventory.byVersion();
        Set<Integer> applied = new LinkedHashSet<>();
        for (Map<String, String> entry : history) {
            if (!"t".equals(entry.get("success"))) {
                findings.add("FLYWAY_FAILED_MIGRATION");
            }
            int version;
            try {
                version = Integer.parseInt(entry.get("version"));
            } catch (RuntimeException exception) {
                findings.add("FLYWAY_UNRECOGNIZED_HISTORY");
                continue;
            }
            MigrationInventory.Migration expected = known.get(version);
            if (expected == null || !"SQL".equals(entry.get("type"))
                    || !expected.script().equals(entry.get("script"))
                    || !Integer.toString(expected.checksum()).equals(entry.get("checksum"))
                    || !applied.add(version)) {
                findings.add("FLYWAY_MIGRATION_MISMATCH");
            }
        }
        int lastApplied = applied.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (known.keySet().stream().anyMatch(version -> version <= lastApplied && !applied.contains(version))) {
            findings.add("FLYWAY_MIGRATION_GAP");
        }
        if (!tables.containsAll(inventory.tablesThrough(lastApplied))) {
            findings.add("MIGRATED_TABLES_MISSING");
        }
        Set<String> expectedTables = new LinkedHashSet<>(inventory.tablesThrough(lastApplied));
        expectedTables.add("flyway_schema_history");
        if (!expectedTables.containsAll(tables)) {
            findings.add("TABLES_AHEAD_OF_FLYWAY_HISTORY");
        }
    }

    private static Map<String, String> row(ResultSet rows, String... columns) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String column : columns) {
            result.put(column, rows.getString(column));
        }
        return result;
    }

    private static String qualified(String schema, String table) {
        return '"' + schema.replace("\"", "\"\"") + "\".\"" + table.replace("\"", "\"\"") + '"';
    }

    private static void query(Connection connection, String sql, RowReader reader) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(MAX_ROWS + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    reader.read(rows);
                }
            }
        }
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet rows) throws SQLException;
    }

    record Report(Map<String, String> facts, List<Map<String, String>> schemas,
                  List<Map<String, String>> relations, List<Map<String, String>> migrations,
                  List<Map<String, String>> festivals, List<Map<String, String>> revisions,
                  Map<String, Boolean> catalogPresence, List<String> findings) {
        boolean requiresReview() {
            return !findings.isEmpty();
        }

        void print(PrintStream output) {
            output.println("mode=READ_ONLY_DATABASE_PREFLIGHT");
            output.println("status=" + (requiresReview() ? "STOP_AND_REVIEW" : "READ_ONLY_DATABASE_PREFLIGHT_COMPLETE"));
            output.println("mutationAuthorized=false");
            output.println("ownershipConfirmation=REQUIRED_FROM_DATABASE_PROVIDER");
            facts.forEach((key, value) -> output.println(key + "=" + safe(value)));
            printRows(output, "schema", schemas);
            printRows(output, "relation", relations);
            printRows(output, "migration", migrations);
            printRows(output, "festival", festivals);
            printRows(output, "revision", revisions);
            catalogPresence.forEach((table, present) -> output.println("catalogPresent." + safe(table) + "=" + present));
            findings.forEach(finding -> output.println("finding=" + finding));
        }

        private static void printRows(PrintStream output, String type, List<Map<String, String>> rows) {
            output.println(type + "Count=" + rows.size());
            for (Map<String, String> row : rows) {
                output.println(type + "=" + row.entrySet().stream()
                    .map(entry -> entry.getKey() + ":" + safe(entry.getValue()))
                    .reduce((left, right) -> left + " | " + right).orElse(""));
            }
        }

        private static String safe(String value) {
            if (value == null) {
                return "null";
            }
            StringBuilder result = new StringBuilder();
            value.codePoints().limit(256).forEach(code -> {
                if (Character.isISOControl(code) || code == '|' || code == '\\') {
                    result.append(String.format("\\u%04x", code));
                } else {
                    result.appendCodePoint(code);
                }
            });
            return result.toString();
        }
    }
}

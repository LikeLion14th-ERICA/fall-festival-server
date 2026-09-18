package dev.espero.festival.preflight;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Reads bundled SQL as text only; never loads or calls Flyway. */
record MigrationInventory(List<MigrationInventory.Migration> migrations) {
    private static final String DIRECTORY = "db/migration";
    private static final Pattern FILE = Pattern.compile("V([0-9]+)__[^/]+\\.sql");
    private static final Pattern TABLE = Pattern.compile(
        "(?im)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)\\s*\\(");

    static MigrationInventory load() throws Exception {
        ClassLoader loader = MigrationInventory.class.getClassLoader();
        Set<String> names = new LinkedHashSet<>();
        var resources = loader.getResources(DIRECTORY);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if (resource.getProtocol().equals("file")) {
                try (var files = Files.list(Path.of(resource.toURI()))) {
                    files.map(path -> path.getFileName().toString())
                        .filter(name -> FILE.matcher(name).matches()).forEach(names::add);
                }
            } else if (resource.openConnection() instanceof JarURLConnection jar) {
                String prefix = jar.getEntryName() + "/";
                // Closing a cached classloader JarFile would break later resource loading.
                jar.setUseCaches(false);
                try (var archive = jar.getJarFile()) {
                    archive.stream().map(entry -> entry.getName())
                        .filter(name -> name.startsWith(prefix))
                        .map(name -> name.substring(prefix.length()))
                        .filter(name -> FILE.matcher(name).matches()).forEach(names::add);
                }
            } else {
                throw new IOException("Unsupported migration resource protocol.");
            }
        }
        List<Migration> result = new ArrayList<>();
        for (String name : names) {
            var match = FILE.matcher(name);
            if (!match.matches()) {
                continue;
            }
            var stream = loader.getResourceAsStream(DIRECTORY + "/" + name);
            if (stream == null) {
                throw new IOException("Missing migration resource.");
            }
            CRC32 checksum = new CRC32();
            StringBuilder sql = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sql.isEmpty() && line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    checksum.update(line.getBytes(StandardCharsets.UTF_8));
                    sql.append(line).append('\n');
                }
            }
            Set<String> tables = new LinkedHashSet<>();
            var table = TABLE.matcher(sql);
            while (table.find()) {
                tables.add(table.group(1));
            }
            result.add(new Migration(Integer.parseInt(match.group(1)), name, (int) checksum.getValue(), Set.copyOf(tables)));
        }
        result.sort(Comparator.comparingInt(Migration::version));
        if (result.isEmpty() || result.stream().map(Migration::version).distinct().count() != result.size()) {
            throw new IOException("Missing or ambiguous migration inventory.");
        }
        return new MigrationInventory(List.copyOf(result));
    }

    Map<Integer, Migration> byVersion() {
        Map<Integer, Migration> result = new LinkedHashMap<>();
        migrations.forEach(migration -> result.put(migration.version(), migration));
        return result;
    }

    Set<String> tablesThrough(int version) {
        Set<String> tables = new LinkedHashSet<>();
        migrations.stream().filter(migration -> migration.version() <= version)
            .forEach(migration -> tables.addAll(migration.createdTables()));
        return tables;
    }

    record Migration(int version, String script, int checksum, Set<String> createdTables) {}
}

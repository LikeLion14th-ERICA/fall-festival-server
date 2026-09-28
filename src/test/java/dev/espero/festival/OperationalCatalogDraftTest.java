package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The 2026 Hanyang festival catalog draft holds only confirmed content, and
 * the values nobody could supply yet are null. This keeps the draft honest in
 * both directions: the gaps are exactly the documented ones, and filling only
 * those gaps yields a manifest the import validator accepts.
 */
class OperationalCatalogDraftTest {

    private static final Path DRAFT = Path.of("ops/catalog/hanyang-2026/catalog-draft.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    private Path tempDir;

    @Test
    void isNotImportableUntilTheDocumentedGapsAreFilled() {
        CatalogManifestReader reader = new CatalogManifestReader(new CatalogManifestValidator());
        assertThatThrownBy(() -> reader.read(DRAFT, UUID.randomUUID()))
            .isInstanceOf(CatalogCliException.class);
    }

    @Test
    void validatesOnceOnlyTheDocumentedGapsAreFilled() throws IOException {
        ObjectNode draft = (ObjectNode) JSON.readTree(Files.readString(DRAFT));
        List<String> gaps = new ArrayList<>();

        for (JsonNode artist : draft.path("artists")) {
            assertThat(artist.path("imageUrl").isNull()).isFalse();
            assertThat(artist.path("imageWidth").asInt()).isPositive();
            assertThat(artist.path("imageHeight").asInt()).isPositive();
        }
        for (JsonNode performance : draft.path("performances")) {
            String date = performance.path("festivalDate").asString();
            if ("performance-sgaeting".equals(performance.path("id").asString())) {
                assertThat(performance.path("startsAt").asString())
                    .isEqualTo("2026-09-29T17:30:00+09:00");
                gaps.add(fill((ObjectNode) performance, "endsAt", date + "T19:30:00+09:00"));
            } else {
                assertThat(performance.path("startsAt").isNull()).isFalse();
                assertThat(performance.path("endsAt").isNull()).isFalse();
            }
        }

        assertThat(gaps).as("every filled field was a null gap in the draft").doesNotContainNull();
        Path filled = tempDir.resolve("filled.json");
        Files.writeString(filled, JSON.writeValueAsString(draft));
        CatalogManifest manifest = new CatalogManifestReader(new CatalogManifestValidator())
            .read(filled, UUID.randomUUID()).manifest();

        assertThat(manifest.spaces()).hasSize(68);
        assertThat(manifest.spaceMenuItems()).hasSize(24);
        assertThat(manifest.artists()).hasSize(16);
        assertThat(manifest.stampGuideTranslations()).hasSize(2);
    }

    /** Fills a field that must still be null in the draft; returns null otherwise. */
    private static String fill(ObjectNode row, String field, Object value) {
        if (!row.has(field) || !row.get(field).isNull()) {
            return null;
        }
        row.putPOJO(field, value);
        return field;
    }

}

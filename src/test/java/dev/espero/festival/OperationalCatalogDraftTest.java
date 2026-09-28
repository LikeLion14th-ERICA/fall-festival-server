package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 2026 Hanyang festival catalog draft contains confirmed content and
 * keeps optional, unapproved content empty.
 */
class OperationalCatalogDraftTest {

    private static final Path DRAFT = Path.of("ops/catalog/hanyang-2026/catalog-draft.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void validatesConfirmedOperationalDraft() {
        CatalogManifest manifest = new CatalogManifestReader(new CatalogManifestValidator())
            .read(DRAFT, UUID.randomUUID()).manifest();

        assertThat(manifest.spaces()).hasSize(68);
        assertThat(manifest.spaceMenuItems()).hasSize(24);
        assertThat(manifest.artists()).hasSize(16);
        assertThat(manifest.stampGuideTranslations()).hasSize(2);
    }

    @Test
    void retainsConfirmedSgaetingTimeAndOptionalImages() throws IOException {
        JsonNode draft = JSON.readTree(Files.readString(DRAFT));
        boolean found = false;
        for (JsonNode performance : draft.path("performances")) {
            if ("performance-sgaeting".equals(performance.path("id").asString())) {
                found = true;
                assertThat(performance.path("startsAt").asString())
                    .isEqualTo("2026-09-29T18:30:00+09:00");
                assertThat(performance.path("endsAt").asString())
                    .isEqualTo("2026-09-29T19:30:00+09:00");
            }
        }
        assertThat(found).isTrue();
        for (JsonNode space : draft.path("spaces")) {
            assertThat(space.path("imageUrl").isNull()).isTrue();
        }
        for (JsonNode artist : draft.path("artists")) {
            assertThat(artist.path("imageUrl").isNull()).isFalse();
            assertThat(artist.path("imageWidth").asInt()).isPositive();
            assertThat(artist.path("imageHeight").asInt()).isPositive();
        }
    }

}

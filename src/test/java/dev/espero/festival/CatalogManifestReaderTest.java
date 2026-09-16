package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CatalogManifestReaderTest {

    @Test
    void readsLocalManifestAndReturnsItsAuditDigest() throws Exception {
        Path file = Files.createTempFile("catalog-manifest-", ".json");
        try {
            Files.writeString(file, """
                {
                  "festivalId": "ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
                  "festivalDays": [], "spaces": [], "spaceTranslations": [],
                  "spaceSortOrders": [], "spaceEvents": [], "spaceMenuItems": [],
                  "places": [], "placeTranslations": [], "maps": [], "mapTranslations": [],
                  "mapAssets": [], "mapAreas": [], "mapPins": [], "mapPinTranslations": [],
                  "mapPinFilterGroupTranslations": [], "spaceMapTargets": [],
                  "ticketGuide": { "instructions": [] },
                  "stampGuide": {
                    "title": "스탬프투어", "instructions": [], "rewardName": "기념품",
                    "rewardNotice": "수량 소진 시 종료", "qrValue": "PUBLIC-COMMON-QR"
                  }
                }
                """);

            CatalogManifestReader.ManifestDocument document = new CatalogManifestReader(
                new CatalogManifestValidator()
            ).read(file);

            assertThat(document.manifest().festivalDays()).isEmpty();
            assertThat(document.manifest().stampGuide().qrValue()).isEqualTo("PUBLIC-COMMON-QR");
            assertThat(document.sha256()).hasSize(64).matches("[0-9a-f]{64}");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsReceiptCodeFieldsInsteadOfPersistingThem() throws Exception {
        Path file = Files.createTempFile("catalog-manifest-secret-", ".json");
        try {
            Files.writeString(file, """
                {
                  "festivalId": "ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
                  "festivalDays": [], "spaces": [], "spaceTranslations": [],
                  "spaceSortOrders": [], "spaceEvents": [], "spaceMenuItems": [],
                  "places": [], "placeTranslations": [], "maps": [], "mapTranslations": [],
                  "mapAssets": [], "mapAreas": [], "mapPins": [], "mapPinTranslations": [],
                  "mapPinFilterGroupTranslations": [], "spaceMapTargets": [],
                  "ticketGuide": { "instructions": [] },
                  "stampGuide": {
                    "title": "스탬프투어", "instructions": [], "rewardName": "기념품",
                    "rewardNotice": "수량 소진 시 종료", "receiptCode": "secret"
                  }
                }
                """);

            assertThatThrownBy(() -> new CatalogManifestReader(new CatalogManifestValidator()).read(file))
                .isInstanceOf(CatalogCliException.class)
                .hasMessageContaining("valid JSON");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

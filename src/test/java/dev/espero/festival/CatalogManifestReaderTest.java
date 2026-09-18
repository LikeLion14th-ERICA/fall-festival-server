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
                  "artists": [], "artistTranslations": [], "artistLinks": [],
                  "artistLinkTranslations": [], "artistSongs": [], "artistSongTranslations": [],
                  "performances": [], "performanceTranslations": [], "performanceArtists": [],
                  "prohibitedItems": [], "prohibitedItemTranslations": [], "prohibitedMessages": [],
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
            assertThat(document.manifest().artists()).isEmpty();
            assertThat(document.manifest().timetableConfig()).isNull();
            assertThat(document.manifest().stampGuide().qrValue()).isEqualTo("PUBLIC-COMMON-QR");
            assertThat(document.sha256()).hasSize(64).matches("[0-9a-f]{64}");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsTicketAccountFieldsThatNowBelongToOperationalSettings() throws Exception {
        Path file = Files.createTempFile("catalog-manifest-account-", ".json");
        try {
            Files.writeString(file, """
                {
                  "festivalId": "ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
                  "festivalDays": [], "spaces": [], "spaceTranslations": [],
                  "spaceSortOrders": [], "spaceEvents": [], "spaceMenuItems": [],
                  "places": [], "placeTranslations": [], "maps": [], "mapTranslations": [],
                  "mapAssets": [], "mapAreas": [], "mapPins": [], "mapPinTranslations": [],
                  "mapPinFilterGroupTranslations": [], "spaceMapTargets": [],
                  "artists": [], "artistTranslations": [], "artistLinks": [],
                  "artistLinkTranslations": [], "artistSongs": [], "artistSongTranslations": [],
                  "performances": [], "performanceTranslations": [], "performanceArtists": [],
                  "prohibitedItems": [], "prohibitedItemTranslations": [], "prohibitedMessages": [],
                  "ticketGuide": {
                    "instructions": [], "accountBankName": "은행",
                    "accountNumber": "000-0000", "accountHolder": "예금주"
                  },
                  "stampGuide": {
                    "title": "스탬프투어", "instructions": [], "rewardName": "기념품",
                    "rewardNotice": "수량 소진 시 종료"
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
                  "artists": [], "artistTranslations": [], "artistLinks": [],
                  "artistLinkTranslations": [], "artistSongs": [], "artistSongTranslations": [],
                  "performances": [], "performanceTranslations": [], "performanceArtists": [],
                  "prohibitedItems": [], "prohibitedItemTranslations": [], "prohibitedMessages": [],
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

    @Test
    void parsesPerformanceJavaTimeFields() throws Exception {
        Path file = Files.createTempFile("performance-manifest-", ".json");
        try {
            Files.writeString(file, completePerformanceJson("[]"));

            CatalogManifest manifest = new CatalogManifestReader(
                new CatalogManifestValidator()
            ).read(file).manifest();

            assertThat(manifest.performances().getFirst().festivalDate())
                .isEqualTo(java.time.LocalDate.of(2030, 10, 1));
            assertThat(manifest.performances().getFirst().startsAt())
                .isEqualTo(java.time.OffsetDateTime.parse("2030-10-01T21:30:00+09:00"));
            assertThat(manifest.timetableConfig().axisStartTime())
                .isEqualTo(java.time.LocalTime.of(17, 0));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsMissingOrNullRequiredPerformanceCollections() throws Exception {
        Path missing = Files.createTempFile("performance-manifest-missing-", ".json");
        Path nullList = Files.createTempFile("performance-manifest-null-", ".json");
        try {
            Files.writeString(missing, completePerformanceJson("__OMIT__")
                .replace("                  \"artists\": __OMIT__,\n", ""));
            Files.writeString(nullList, completePerformanceJson("null"));

            assertThatThrownBy(() -> reader().read(missing))
                .isInstanceOf(CatalogCliException.class)
                .hasMessageContaining("valid JSON");
            assertThatThrownBy(() -> reader().read(nullList))
                .isInstanceOf(CatalogCliException.class)
                .hasMessageContaining("valid JSON");
        } finally {
            Files.deleteIfExists(missing);
            Files.deleteIfExists(nullList);
        }
    }

    @Test
    void includesPerformanceContentInTheRawManifestDigest() throws Exception {
        Path first = Files.createTempFile("performance-hash-one-", ".json");
        Path second = Files.createTempFile("performance-hash-two-", ".json");
        try {
            String source = completePerformanceJson("[]");
            Files.writeString(first, source);
            Files.writeString(second, source.replace("\"title\": \"공연\"", "\"title\": \"다른 공연\""));

            assertThat(reader().read(first).sha256()).isNotEqualTo(reader().read(second).sha256());
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    private CatalogManifestReader reader() {
        return new CatalogManifestReader(new CatalogManifestValidator());
    }

    private String completePerformanceJson(String artists) {
        return """
                {
                  "festivalId": "ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
                  "festivalDays": [{
                    "festivalDate": "2030-10-01",
                    "opensAt": "2030-10-01T10:00:00+09:00",
                    "closesAt": "2030-10-02T01:00:00+09:00"
                  }],
                  "spaces": [], "spaceTranslations": [], "spaceSortOrders": [],
                  "spaceEvents": [], "spaceMenuItems": [], "places": [],
                  "placeTranslations": [], "maps": [], "mapTranslations": [],
                  "mapAssets": [], "mapAreas": [], "mapPins": [], "mapPinTranslations": [],
                  "mapPinFilterGroupTranslations": [], "spaceMapTargets": [],
                  "artists": %s,
                  "artistTranslations": [], "artistLinks": [], "artistLinkTranslations": [],
                  "artistSongs": [], "artistSongTranslations": [],
                  "performances": [{
                    "id": "night-show", "festivalDate": "2030-10-01",
                    "startsAt": "2030-10-01T21:30:00+09:00",
                    "endsAt": "2030-10-02T00:30:00+09:00"
                  }],
                  "performanceTranslations": [{
                    "performanceId": "night-show", "locale": "ko", "title": "공연"
                  }],
                  "performanceArtists": [],
                  "timetableConfig": {"axisStartTime": "17:00:00", "axisEndTime": "23:00:00"},
                  "prohibitedItems": [], "prohibitedItemTranslations": [], "prohibitedMessages": [],
                  "ticketGuide": { "instructions": [] },
                  "stampGuide": {
                    "title": "스탬프투어", "instructions": [], "rewardName": "기념품",
                    "rewardNotice": "수량 소진 시 종료"
                  }
                }
                """.formatted(artists);
    }
}

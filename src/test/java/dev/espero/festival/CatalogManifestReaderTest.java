package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogManifestReaderTest {

    private static final UUID DEVELOPMENT_FIXTURE_FESTIVAL_ID = UUID.fromString(
        "00000000-0000-4000-8000-000000000001"
    );

    @Test
    void readsDevelopmentCatalogWithExplicitFestivalIdOverride() {
        Path manifestPath = Path.of("dev", "catalog", "development-catalog.json");

        CatalogManifestReader.ManifestDocument document = reader().read(
            manifestPath, DEVELOPMENT_FIXTURE_FESTIVAL_ID
        );
        CatalogManifest manifest = document.manifest();

        assertThat(manifest.festivalId()).isNull();
        assertThat(document.festivalId()).isEqualTo(DEVELOPMENT_FIXTURE_FESTIVAL_ID);
        assertThat(manifest.festivalDays())
            .extracting(CatalogManifest.FestivalDay::festivalDate)
            .containsExactly(
                LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 1)
            );
        assertThat(manifest.artists()).hasSize(2);
        assertThat(manifest.performances()).hasSize(2);
        assertThat(manifest.performances())
            .extracting(CatalogManifest.Performance::festivalDate)
            .containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 1))
            .doesNotContain(LocalDate.of(2026, 9, 30));
        assertThat(manifest.timetableConfig().axisStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(manifest.timetableConfig().axisEndTime()).isEqualTo(LocalTime.of(23, 0));

        CatalogManifest.TicketGuide ticketGuide = manifest.ticketGuide();
        assertThat(ticketGuide.accountBankName()).isNull();
        assertThat(ticketGuide.accountNumber()).isNull();
        assertThat(ticketGuide.accountHolder()).isNull();
        assertThat(ticketGuide.transferLinkLabel()).isNull();
        assertThat(ticketGuide.transferLinkUrl()).isNull();
        assertThat(ticketGuide.unitPriceAmount()).isNull();
        assertThat(manifest.stampGuide().qrValue()).isNull();
    }

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
            assertThat(document.festivalId()).isEqualTo(document.manifest().festivalId());
            assertThat(document.manifest().artists()).isEmpty();
            assertThat(document.manifest().timetableConfig()).isNull();
            assertThat(document.manifest().stampGuide().qrValue()).isEqualTo("PUBLIC-COMMON-QR");
            assertThat(document.sha256()).hasSize(64).matches("[0-9a-f]{64}");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void usesFestivalIdOverrideWhenManifestOmitsIt() throws Exception {
        UUID festivalId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Path file = writeMinimalManifest(null);
        try {
            CatalogManifestReader.ManifestDocument document = reader().read(file, festivalId);

            assertThat(document.manifest().festivalId()).isNull();
            assertThat(document.festivalId()).isEqualTo(festivalId);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void acceptsMatchingManifestAndOverrideFestivalIds() throws Exception {
        UUID festivalId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Path file = writeMinimalManifest(festivalId);
        try {
            assertThat(reader().read(file, festivalId).festivalId()).isEqualTo(festivalId);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsMismatchedManifestAndOverrideFestivalIds() throws Exception {
        UUID manifestFestivalId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID overrideFestivalId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Path file = writeMinimalManifest(manifestFestivalId);
        try {
            assertThatThrownBy(() -> reader().read(file, overrideFestivalId))
                .isInstanceOf(CatalogCliException.class)
                .hasMessage("Manifest festivalId does not match --festival-id.");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsMissingManifestAndOverrideFestivalIds() throws Exception {
        Path file = writeMinimalManifest(null);
        try {
            assertThatThrownBy(() -> reader().read(file))
                .isInstanceOf(CatalogCliException.class)
                .hasMessage("Festival ID is required in the manifest or --festival-id.");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsTopLevelNullAsAControlledValidationFailure() throws Exception {
        Path file = Files.createTempFile("catalog-null-manifest-", ".json");
        try {
            Files.writeString(file, "null");

            assertThatThrownBy(() -> reader().read(file))
                .isInstanceOf(CatalogCliException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessage("Manifest validation failed: manifest is required");
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

    private Path writeMinimalManifest(UUID festivalId) throws Exception {
        Path file = Files.createTempFile("catalog-binding-", ".json");
        String festivalIdProperty = festivalId == null
            ? ""
            : "  \"festivalId\": \"" + festivalId + "\",\n";
        Files.writeString(file, """
            {
            %s  "festivalDays": [], "spaces": [], "spaceTranslations": [],
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
                "rewardNotice": "수량 소진 시 종료"
              }
            }
            """.formatted(festivalIdProperty));
        return file;
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

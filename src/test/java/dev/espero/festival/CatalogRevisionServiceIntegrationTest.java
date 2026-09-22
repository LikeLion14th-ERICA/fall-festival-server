package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.CatalogSnapshotStore;
import dev.espero.festival.persistence.LocaleCompletenessStore;
import dev.espero.festival.persistence.PerformanceRevisionValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the complete developer-only catalog revision workflow in PostgreSQL. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class CatalogRevisionServiceIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID INITIAL_REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CatalogRevisionService revisions;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private CatalogSnapshotStore snapshots;

    @Autowired
    private PerformanceRevisionValidator performanceRevisions;

    @Autowired
    private CatalogExportService exports;

    @Autowired
    private LocaleCompletenessStore localeCompleteness;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void resetRevisionWorkflowData() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> resetRevisionWorkflowDataInTransaction());
    }

    private void resetRevisionWorkflowDataInTransaction() {
        jdbc.update("DELETE FROM catalog_revision_audit", Map.of());
        jdbc.update("DELETE FROM crowding_state", Map.of());

        MapSqlParameterSource parameters = new MapSqlParameterSource("initialRevisionId", INITIAL_REVISION_ID);
        for (String table : new String[] {
            "prohibited_messages",
            "prohibited_item_translations",
            "prohibited_items",
            "performance_artists",
            "performance_translations",
            "performances",
            "artist_song_translations",
            "artist_songs",
            "artist_link_translations",
            "artist_links",
            "artist_translations",
            "artists",
            "timetable_configs",
            "festival_title_translations",
            "ticket_guide_translations",
            "stamp_guide_translations",
            "stamp_booth_tokens",
            "stamp_booths",
            "map_asset_translations",
            "ticket_guide_revisions",
            "stamp_guide_revisions",
            "space_map_targets",
            "map_pin_translations",
            "map_pin_filter_group_translations",
            "map_pins",
            "map_areas",
            "map_translations",
            "map_asset_versions",
            "maps",
            "place_translations",
            "places",
            "space_menu_items",
            "space_events",
            "space_sort_orders",
            "space_translations",
            "spaces",
            "festival_link_translations",
            "festival_links",
            "festival_days"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE festival_revision_id <> :initialRevisionId", parameters);
        }
        jdbc.update(
            "UPDATE festival_revisions SET state = 'archived' WHERE id <> :initialRevisionId", parameters
        );
        jdbc.update(
            "DELETE FROM festival_revisions WHERE id <> :initialRevisionId", parameters
        );
        jdbc.update(
            "UPDATE festival_revisions SET state = 'published' WHERE id = :initialRevisionId", parameters
        );
    }

    @Test
    void refusesAnImportWhoseBaselineIsNotTheCurrentPublishedRevision() {
        UUID published = currentPublishedRevision();
        assertThat(published).isNotNull();

        assertThatThrownBy(() -> importManifest("qr-stale", "/assets/maps/overview-v1.png", UUID.randomUUID()))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("BASE_REVISION_CONFLICT");
        assertThatThrownBy(() -> importManifest("qr-none", "/assets/maps/overview-v1.png", null))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("BASE_REVISION_CONFLICT");
        assertThat(revisionCount()).isEqualTo(1);
    }

    @Test
    void refusesToPublishADraftPreparedBeforeAnotherPublication() throws IOException {
        UUID firstDraft = importManifest("qr-first", "/assets/maps/overview-v1.png");
        UUID secondDraft = importManifest("qr-second", "/assets/maps/overview-v1.png");

        revisions.publish(secondDraft, "release-bot");

        assertThatThrownBy(() -> revisions.publish(firstDraft, "release-bot"))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("BASE_REVISION_CONFLICT");
        assertThat(revisionState(secondDraft)).isEqualTo("published");
        assertThat(revisionState(firstDraft)).isEqualTo("draft");
    }

    @Test
    void refusesARollbackWhoseExpectedCurrentRevisionIsStale() throws IOException {
        UUID source = importManifest("qr-source", "/assets/maps/overview-v1.png");
        revisions.publish(source, "release-bot");
        UUID replacement = importManifest("qr-replacement", "/assets/maps/overview-v1.png");
        revisions.publish(replacement, "release-bot");

        assertThatThrownBy(() -> revisions.rollback(source, source, "incident-bot"))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("BASE_REVISION_CONFLICT");
        assertThat(revisionState(replacement)).isEqualTo("published");
        assertThat(revisionState(source)).isEqualTo("archived");
    }

    @Test
    void recordsTheReplacedPublicationAsTheBaselineOfARollbackRevision() throws IOException {
        UUID source = importManifest("qr-base-source", "/assets/maps/overview-v1.png");
        revisions.publish(source, "release-bot");
        UUID replacement = importManifest("qr-base-replacement", "/assets/maps/overview-v1.png");
        revisions.publish(replacement, "release-bot");

        UUID rollback = revisions.rollback(source, replacement, "incident-bot");

        assertThat(baseRevision(rollback)).isEqualTo(replacement);
        assertThat(baseRevision(replacement)).isEqualTo(source);
        assertThat(revisionState(rollback)).isEqualTo("published");
    }

    @Test
    void exportsAStoredRevisionAndReimportsItWithoutSemanticLoss() throws IOException {
        UUID published = importManifest("qr-roundtrip", "/assets/maps/overview-v1.png");
        revisions.publish(published, "release-bot");

        CatalogManifest exported = exports.export(published).manifest();
        UUID reimported = importExportedManifest(exported);
        CatalogManifest exportedAgain = exports.export(reimported).manifest();

        assertThat(exportedAgain).isEqualTo(exported);
        assertThat(exported.baselineRevisionId()).isEqualTo(published);
        assertThat(exported.artists()).isNotEmpty();
        assertThat(exported.performances()).isNotEmpty();
        assertThat(exported.performanceArtists()).isNotEmpty();
        assertThat(exported.timetableConfig()).isNotNull();
        assertThat(exported.mapAssets()).isNotEmpty();
        assertThat(exported.spaceMapTargets()).isNotEmpty();
        assertThat(exported.stampGuide()).isNotNull();
        for (String table : performanceTables()) {
            assertThat(rowCount(table, reimported)).as(table).isEqualTo(rowCount(table, published));
        }
    }

    @Test
    void keepsAPlacePinOutsideTheDesignFiltersThroughExportAndPublish() throws IOException {
        UUID revisionId = importManifest("qr-unfiltered-pin", "/assets/maps/overview-v1.png");
        jdbc.update("""
            UPDATE map_pins SET filter_group = NULL
            WHERE festival_revision_id = :revisionId AND place_id IS NOT NULL
            """, new MapSqlParameterSource("revisionId", revisionId));
        jdbc.update(
            "DELETE FROM map_pin_filter_group_translations WHERE festival_revision_id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId)
        );

        revisions.publish(revisionId, "release-bot");

        CatalogExportService.ExportResult result = exports.export(revisionId);
        UUID reimported = importExportedManifest(result.manifest());
        revisions.publish(reimported, "release-bot");

        assertThat(result.findings()).isEmpty();
        assertThat(result.manifest().mapPins())
            .filteredOn(pin -> pin.placeId() != null)
            .isNotEmpty()
            .allSatisfy(pin -> assertThat(pin.filterGroup()).isNull());
        CatalogSnapshot published = snapshots.loadPublished();
        for (CatalogManifest.MapPin pin : result.manifest().mapPins()) {
            assertThat(published.filtersFor(pin.mapId(), pin.mapVersion())).isEmpty();
        }
    }

    @Test
    void rejectsAnImportThatUsesAFilterGroupOutsideTheDesign() throws IOException {
        Path manifest = tempDir.resolve("old-filter.json");
        String json = manifestJson("qr-old-filter", "/assets/maps/overview-v1.png", currentPublishedRevision());
        assertThat(json).contains("\"PHOTO_BOOTH\"");
        Files.writeString(manifest, json.replace("\"PHOTO_BOOTH\"", "\"EXPERIENCE\""));

        assertThatThrownBy(() -> revisions.importManifest(manifest, "release-bot"))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("Unsupported map pin filter group");
    }

    @Test
    void publishesAFoodTruckWithAMenuAndAPromotionBoothWithEvents() throws IOException {
        String base = manifestJson("qr-categories", "/assets/maps/overview-v1.png", currentPublishedRevision());
        String foodTruck = base
            .replace("\"category\": \"BOOTH\"", "\"category\": \"FOOD_TRUCK\"")
            .replace("\"spaceMenuItems\": []", """
                "spaceMenuItems": [
                  {"spaceId": "space-booth", "locale": "ko", "sortOrder": 1, "name": "닭꼬치", "priceAmount": 5000}
                ]""");
        UUID foodTruckRevision = importJson(foodTruck);
        revisions.publish(foodTruckRevision, "release-bot");
        assertThat(snapshots.loadPublished().findSpace("space-booth")).hasValueSatisfying(space -> {
            assertThat(space.category()).isEqualTo("FOOD_TRUCK");
            assertThat(space.menu()).hasSize(1);
        });

        String promotion = manifestJson("qr-categories-2", "/assets/maps/overview-v1.png", currentPublishedRevision())
            .replace("\"category\": \"BOOTH\"", "\"category\": \"PROMOTION_BOOTH\"")
            .replace("\"spaceEvents\": []", """
                "spaceEvents": [
                  {"spaceId": "space-booth", "locale": "ko", "sortOrder": 1, "content": "경품 추첨"}
                ]""");
        UUID promotionRevision = importJson(promotion);
        revisions.publish(promotionRevision, "release-bot");
        assertThat(snapshots.loadPublished().findSpace("space-booth")).hasValueSatisfying(space -> {
            assertThat(space.category()).isEqualTo("PROMOTION_BOOTH");
            assertThat(space.events()).containsExactly("경품 추첨");
        });
    }

    @Test
    void rejectsEventsOnAFleaMarketAndAMenuOnAStudentCouncilBooth() throws IOException {
        String fleaMarket = manifestJson("qr-flea", "/assets/maps/overview-v1.png", currentPublishedRevision())
            .replace("\"category\": \"BOOTH\"", "\"category\": \"FLEA_MARKET\"")
            .replace("\"spaceEvents\": []", """
                "spaceEvents": [
                  {"spaceId": "space-booth", "locale": "ko", "sortOrder": 1, "content": "이벤트"}
                ]""");
        String studentCouncil = manifestJson("qr-council", "/assets/maps/overview-v1.png", currentPublishedRevision())
            .replace("\"category\": \"BOOTH\"", "\"category\": \"STUDENT_COUNCIL_BOOTH\"")
            .replace("\"spaceMenuItems\": []", """
                "spaceMenuItems": [
                  {"spaceId": "space-booth", "locale": "ko", "sortOrder": 1, "name": "음료", "priceAmount": 1000}
                ]""");

        assertThatThrownBy(() -> importJson(fleaMarket))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("Only booth-type spaces can contain events");
        assertThatThrownBy(() -> importJson(studentCouncil))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("Only PUB and FOOD_TRUCK spaces can contain menu items");
    }

    private UUID importJson(String json) throws IOException {
        Path manifest = tempDir.resolve(UUID.randomUUID() + ".json");
        Files.writeString(manifest, json);
        return revisions.importManifest(manifest, "release-bot");
    }

    @Test
    void publishesHomeLinksAndRestoresThemWithARollback() throws IOException {
        UUID first = importManifest("qr-links-a", "/assets/maps/overview-v1.png");
        revisions.publish(first, "release-bot");

        CatalogSnapshot.FestivalHome home = snapshots.loadPublished().home();
        assertThat(home.title()).isEqualTo("한양문화제 동심");
        assertThat(home.dates()).isNotEmpty();
        assertThat(home.links()).extracting(CatalogSnapshot.HomeLink::id)
            .containsExactlyInAnyOrder("notices", "instagram", "youtube");
        assertThat(home.links()).filteredOn(link -> link.id().equals("instagram")).singleElement()
            .satisfies(link -> {
                assertThat(link.label()).isEqualTo("Instagram");
                assertThat(link.iconKey()).isEqualTo("instagram");
            });

        String withFaq = manifestJson("qr-links-b", "/assets/maps/overview-v1.png", currentPublishedRevision())
            .replace("\"festivalLinks\": [", """
                "festivalLinks": [
                {"id": "faq", "kind": "FAQ", "url": "https://example.test/faq", "iconKey": null, "sortOrder": 1},""")
            .replace("\"festivalLinkTranslations\": [", """
                "festivalLinkTranslations": [
                {"linkId": "faq", "locale": "ko", "label": "FAQ"},""");
        UUID second = importJson(withFaq);
        revisions.publish(second, "release-bot");
        assertThat(snapshots.loadPublished().home().links()).extracting(CatalogSnapshot.HomeLink::kind).contains("FAQ");

        UUID rollback = revisions.rollback(first, second, "incident-bot");

        assertThat(snapshots.loadPublished().context().revisionId()).isEqualTo(rollback);
        assertThat(snapshots.loadPublished().home().links()).extracting(CatalogSnapshot.HomeLink::id)
            .containsExactlyInAnyOrder("notices", "instagram", "youtube");
        assertThat(exports.export(rollback).manifest().festivalLinkTranslations()).hasSize(4);
    }

    @Test
    void rejectsHomeLinksThatBreakTheLinkRules() throws IOException {
        String base = manifestJson("qr-link-rules", "/assets/maps/overview-v1.png", currentPublishedRevision());
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("Only one festivalLinks row is allowed for UNIVERSITY_NOTICES", base.replace(
            "\"festivalLinks\": [",
            "\"festivalLinks\": [{\"id\": \"notices-2\", \"kind\": \"UNIVERSITY_NOTICES\", "
                + "\"url\": \"https://example.test/n2\", \"iconKey\": null, \"sortOrder\": 2},"
        ).replace(
            "\"festivalLinkTranslations\": [",
            "\"festivalLinkTranslations\": [{\"linkId\": \"notices-2\", \"locale\": \"ko\", \"label\": \"공지 2\"},"
        ));
        cases.put("festivalLinks.url must use https://",
            base.replace("https://example.test/notices", "http://example.test/notices"));
        cases.put("festivalLinks.iconKey is only for OFFICIAL_CHANNEL", base.replace(
            "\"url\": \"https://example.test/notices\", \"iconKey\": null",
            "\"url\": \"https://example.test/notices\", \"iconKey\": \"bell\""));
        cases.put("Every festival link needs a Korean festivalLinkTranslations row",
            base.replace("{\"linkId\": \"youtube\", \"locale\": \"ko\", \"label\": \"YouTube\"}",
                "{\"linkId\": \"youtube\", \"locale\": \"en\", \"label\": \"YouTube\"}"));

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey()).isNotEqualTo(base);
            assertThatThrownBy(() -> importJson(entry.getValue()))
                .as(entry.getKey())
                .isInstanceOf(CatalogCliException.class)
                .hasMessageContaining(entry.getKey());
        }
    }

    @Test
    void importsAndPublishesTheFrontendMockCatalog() {
        UUID revision = revisions.importManifest(
            Path.of("dev", "catalog", "frontend-mock-catalog.json"),
            "release-bot",
            FESTIVAL_ID,
            new CatalogRevisionService.BaselineOverride(currentPublishedRevision())
        );
        revisions.publish(revision, "release-bot");

        CatalogSnapshot snapshot = snapshots.loadPublished();
        assertThat(snapshot.spaces()).hasSize(20);
        assertThat(snapshot.spaces()).extracting(CatalogSnapshot.Space::category).containsOnly(
            "PUB", "BOOTH", "FLEA_MARKET", "FOOD_TRUCK", "STUDENT_COUNCIL_BOOTH", "PROMOTION_BOOTH"
        ).contains("STUDENT_COUNCIL_BOOTH", "PROMOTION_BOOTH", "FOOD_TRUCK");
        assertThat(snapshot.spaces()).allSatisfy(space -> {
            assertThat(space.name()).startsWith("[목]");
            assertThat(space.mapTarget()).isNotNull();
        });
        assertThat(snapshot.maps()).hasSize(4);
        assertThat(snapshot.overviewId()).hasValue("map-mock-overview");
        assertThat(snapshot.ticketMapTarget()).isNotNull();
        assertThat(snapshot.home().links()).hasSize(6);
        assertThat(exports.export(revision).findings()).isEmpty();
    }

    @Test
    void reportsAndBlocksALegacyRevisionWithAPartialTicketSchedule() throws IOException {
        UUID revisionId = importManifest("qr-legacy-ticket", "/assets/maps/overview-v1.png");
        jdbc.update("""
            UPDATE ticket_guide_revisions
            SET festival_start_date = DATE '2026-10-01', festival_end_date = NULL,
                daily_transfer_open_time = NULL, daily_transfer_close_time = NULL,
                daily_pickup_open_time = NULL, daily_pickup_close_time = NULL
            WHERE festival_revision_id = :revisionId AND id = 1
            """, new MapSqlParameterSource("revisionId", revisionId));

        CatalogExportService.ExportResult result = exports.export(revisionId);

        assertThat(result.findings())
            .anyMatch(finding -> finding.startsWith(CatalogExportService.LEGACY_TICKET_SCHEDULE_UNCONFIGURED));
        assertThatThrownBy(() -> importExportedManifest(result.manifest()))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining(CatalogExportService.LEGACY_TICKET_SCHEDULE_UNCONFIGURED);
    }

    @Test
    void publishesACompleteEnglishCatalogAndKeepsItThroughExportAndRollback() throws IOException {
        UUID korean = importManifest("qr-locale-ko", "/assets/maps/overview-v1.png");
        revisions.publish(korean, "release-bot");
        assertThat(localeCompleteness.findings(korean, "en")).isNotEmpty();
        assertThatThrownBy(() -> snapshots.loadRevision(korean, "en"))
            .isInstanceOf(RuntimeException.class);

        CatalogManifest english = withLocale(exports.export(korean).manifest(), "en");
        UUID translated = importExportedManifest(english);
        revisions.publish(translated, "release-bot");

        assertThat(localeCompleteness.findings(translated, "en")).isEmpty();
        assertThat(localeCompleteness.findings(translated, "zh-Hans")).isNotEmpty();
        CatalogSnapshot koreanSnapshot = snapshots.loadPublished();
        CatalogSnapshot englishSnapshot = snapshots.loadPublished("en");
        assertThat(englishSnapshot.home().title()).isEqualTo("en " + koreanSnapshot.home().title());
        assertThat(englishSnapshot.stampGuide().title()).isEqualTo("en " + koreanSnapshot.stampGuide().title());
        assertThat(englishSnapshot.stampGuide().qrValue()).isEqualTo(koreanSnapshot.stampGuide().qrValue());
        assertThat(englishSnapshot.maps()).extracting(map -> map.image().alt())
            .allMatch(alt -> alt.startsWith("en "));
        assertThat(englishSnapshot.spaces()).extracting(CatalogSnapshot.Space::name)
            .allMatch(name -> name.startsWith("en "));
        assertThat(koreanSnapshot.spaces()).extracting(CatalogSnapshot.Space::name)
            .noneMatch(name -> name.startsWith("en "));

        CatalogManifest exported = exports.export(translated).manifest();
        assertThat(exported.festivalTitleTranslations()).hasSize(1);
        assertThat(exported.stampGuideTranslations()).hasSize(1);
        assertThat(exported.ticketGuideTranslations()).hasSize(1);
        assertThat(exported.mapAssetTranslations()).hasSameSizeAs(english.mapAssetTranslations());

        UUID reverted = revisions.rollback(korean, translated, "incident-bot");
        assertThat(localeCompleteness.findings(reverted, "en")).isNotEmpty();
        UUID restored = revisions.rollback(translated, reverted, "incident-bot");
        assertThat(localeCompleteness.findings(restored, "en")).isEmpty();
    }

    @Test
    void reportsEachGapThatWouldLeaveAnEnglishScreenPartlyEmpty() throws IOException {
        UUID korean = importManifest("qr-locale-gaps", "/assets/maps/overview-v1.png");
        CatalogManifest english = withLocale(exports.export(korean).manifest(), "en");
        UUID translated = importExportedManifest(english);
        assertThat(localeCompleteness.findings(translated, "en")).isEmpty();

        MapSqlParameterSource revision = new MapSqlParameterSource("revisionId", translated);
        jdbc.update("""
            DELETE FROM space_translations
            WHERE festival_revision_id = :revisionId AND locale = 'en'
              AND space_id = (SELECT min(space_id) FROM space_translations WHERE festival_revision_id = :revisionId)
            """, revision);
        jdbc.update("DELETE FROM stamp_guide_translations WHERE festival_revision_id = :revisionId", revision);
        jdbc.update("DELETE FROM festival_title_translations WHERE festival_revision_id = :revisionId", revision);

        assertThat(localeCompleteness.findings(translated, "en"))
            .anyMatch(finding -> finding.startsWith("space_translations: 1 "))
            .anyMatch(finding -> finding.startsWith("stamp_guide_translations"))
            .anyMatch(finding -> finding.startsWith("festival_title_translations"));
        assertThatThrownBy(() -> snapshots.loadRevision(translated, "en"))
            .isInstanceOf(RuntimeException.class);
        assertThat(snapshots.loadRevision(translated)).isNotNull();
    }

    @Test
    void rejectsTextTranslationsThatDoNotMatchTheKoreanShape() throws IOException {
        UUID korean = importManifest("qr-locale-shape", "/assets/maps/overview-v1.png");
        CatalogManifest english = withLocale(exports.export(korean).manifest(), "en");
        CatalogManifest.StampGuideTranslation stamp = english.stampGuideTranslations().getFirst();
        CatalogManifest koreanRow = replaceTextTranslations(english,
            List.of(new CatalogManifest.FestivalTitleTranslation("ko", "중복 제목")),
            english.stampGuideTranslations());
        CatalogManifest extraInstruction = replaceTextTranslations(english, english.festivalTitleTranslations(),
            List.of(new CatalogManifest.StampGuideTranslation(
                stamp.locale(), stamp.title(), append(stamp.instructions(), "extra"), stamp.rewardName(),
                stamp.rewardLocationText(), stamp.rewardHoursText(), stamp.rewardNotice()
            )));

        assertThatThrownBy(() -> importExportedManifest(koreanRow))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("non-Korean locales only");
        assertThatThrownBy(() -> importExportedManifest(extraInstruction))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("stampGuideTranslations.instructions must match");
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private static CatalogManifest replaceTextTranslations(
        CatalogManifest m,
        List<CatalogManifest.FestivalTitleTranslation> titles,
        List<CatalogManifest.StampGuideTranslation> stamps
    ) {
        return new CatalogManifest(
            m.festivalId(), m.baselineRevisionId(), m.festivalDays(), m.spaces(), m.spaceTranslations(),
            m.spaceSortOrders(), m.spaceEvents(), m.spaceMenuItems(), m.places(), m.placeTranslations(),
            m.maps(), m.mapTranslations(), m.mapAssets(), m.mapAreas(), m.mapPins(), m.mapPinTranslations(),
            m.mapPinFilterGroupTranslations(), m.spaceMapTargets(), m.artists(), m.artistTranslations(),
            m.artistLinks(), m.artistLinkTranslations(), m.artistSongs(), m.artistSongTranslations(),
            m.performances(), m.performanceTranslations(), m.performanceArtists(), m.timetableConfig(),
            m.prohibitedItems(), m.prohibitedItemTranslations(), m.prohibitedMessages(), m.ticketGuide(),
            m.stampGuide(), m.festivalLinks(), m.festivalLinkTranslations(), titles, m.mapAssetTranslations(),
            m.ticketGuideTranslations(), stamps, m.stampBooths(), m.stampBoothTokens()
        );
    }

    /** Adds a {@code locale} copy of every Korean text, prefixed so tests can tell them apart. */
    private static CatalogManifest withLocale(CatalogManifest m, String locale) {
        java.util.function.UnaryOperator<String> t = value -> value == null ? null : locale + " " + value;
        java.util.function.Predicate<String> ko = "ko"::equals;
        return new CatalogManifest(
            m.festivalId(), m.baselineRevisionId(), m.festivalDays(), m.spaces(),
            plus(m.spaceTranslations(), m.spaceTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.SpaceTranslation(r.spaceId(), locale, t.apply(r.name()),
                    t.apply(r.imageAlt()), t.apply(r.locationText()), t.apply(r.operatorText()),
                    t.apply(r.hoursText()), t.apply(r.descriptionText()), t.apply(r.experienceText()),
                    t.apply(r.contactLabel()), r.contactUrl())).toList()),
            plus(m.spaceSortOrders(), m.spaceSortOrders().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.SpaceSortOrder(locale, r.spaceId(), r.sortRank())).toList()),
            plus(m.spaceEvents(), m.spaceEvents().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.SpaceEvent(r.spaceId(), locale, r.sortOrder(), t.apply(r.content())))
                .toList()),
            plus(m.spaceMenuItems(), m.spaceMenuItems().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.SpaceMenuItem(r.spaceId(), locale, r.sortOrder(), t.apply(r.name()),
                    r.priceAmount())).toList()),
            m.places(),
            plus(m.placeTranslations(), m.placeTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.PlaceTranslation(r.placeId(), locale, t.apply(r.name()),
                    t.apply(r.locationText()), t.apply(r.hoursText()), t.apply(r.descriptionText()),
                    t.apply(r.usageText()))).toList()),
            m.maps(),
            plus(m.mapTranslations(), m.mapTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.MapTranslation(r.mapId(), locale, t.apply(r.name()))).toList()),
            m.mapAssets(), m.mapAreas(), m.mapPins(),
            plus(m.mapPinTranslations(), m.mapPinTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.MapPinTranslation(r.mapId(), r.mapVersion(), r.pinId(), locale,
                    t.apply(r.label()))).toList()),
            plus(m.mapPinFilterGroupTranslations(), m.mapPinFilterGroupTranslations().stream()
                .filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.MapPinFilterGroupTranslation(r.filterGroup(), locale,
                    t.apply(r.label()))).toList()),
            m.spaceMapTargets(), m.artists(),
            plus(m.artistTranslations(), m.artistTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.ArtistTranslation(r.artistId(), locale, t.apply(r.name()),
                    t.apply(r.imageAlt()), t.apply(r.introduction()))).toList()),
            m.artistLinks(),
            plus(m.artistLinkTranslations(), m.artistLinkTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.ArtistLinkTranslation(r.artistId(), r.sortOrder(), locale,
                    t.apply(r.label()))).toList()),
            m.artistSongs(),
            plus(m.artistSongTranslations(), m.artistSongTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.ArtistSongTranslation(r.artistId(), r.sortOrder(), locale,
                    t.apply(r.title()))).toList()),
            m.performances(),
            plus(m.performanceTranslations(), m.performanceTranslations().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.PerformanceTranslation(r.performanceId(), locale, t.apply(r.title()),
                    t.apply(r.description()))).toList()),
            m.performanceArtists(), m.timetableConfig(), m.prohibitedItems(),
            plus(m.prohibitedItemTranslations(), m.prohibitedItemTranslations().stream()
                .filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.ProhibitedItemTranslation(r.itemId(), locale, t.apply(r.label())))
                .toList()),
            plus(m.prohibitedMessages(), m.prohibitedMessages().stream().filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.ProhibitedMessage(locale, t.apply(r.message()))).toList()),
            m.ticketGuide(), m.stampGuide(), m.festivalLinks(),
            plus(m.festivalLinkTranslations(), m.festivalLinkTranslations().stream()
                .filter(r -> ko.test(r.locale()))
                .map(r -> new CatalogManifest.FestivalLinkTranslation(r.linkId(), locale, t.apply(r.label())))
                .toList()),
            List.of(new CatalogManifest.FestivalTitleTranslation(locale, t.apply("한양문화제 동심"))),
            m.mapAssets().stream()
                .map(a -> new CatalogManifest.MapAssetTranslation(a.mapId(), a.version(), locale,
                    t.apply(a.imageAlt()))).toList(),
            List.of(new CatalogManifest.TicketGuideTranslation(locale,
                m.ticketGuide().instructions().stream().map(t).toList())),
            List.of(new CatalogManifest.StampGuideTranslation(locale, t.apply(m.stampGuide().title()),
                m.stampGuide().instructions().stream().map(t).toList(), t.apply(m.stampGuide().rewardName()),
                t.apply(m.stampGuide().rewardLocationText()), t.apply(m.stampGuide().rewardHoursText()),
                t.apply(m.stampGuide().rewardNotice()))),
            m.stampBooths(), m.stampBoothTokens()
        );
    }

    /** Keeps the rows of other locales and replaces those in the added rows' locale. */
    private static <T extends Record> List<T> plus(List<T> first, List<T> added) {
        java.util.Set<String> locales = new java.util.HashSet<>();
        added.forEach(row -> locales.add(localeOf(row)));
        List<T> result = new ArrayList<>(first.stream().filter(row -> !locales.contains(localeOf(row))).toList());
        result.addAll(added);
        return result;
    }

    private static String localeOf(Record row) {
        try {
            return (String) row.getClass().getMethod("locale").invoke(row);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void importsBoothStampsAndKeepsThemThroughExportAndRollback() throws IOException {
        UUID first = importManifest("qr-stamps-a", "/assets/maps/overview-v1.png");
        revisions.publish(first, "release-bot");
        String hashA = "a".repeat(64);
        String hashB = "b".repeat(64);
        UUID withStamps = importJson(withStampBooths(
            manifestJson("qr-stamps-b", "/assets/maps/overview-v1.png", currentPublishedRevision()),
            """
            [{"id": "likelion", "name": "멋사 부스", "sortOrder": 1},
             {"id": "photo", "name": "포토부스", "sortOrder": 2}]
            """,
            """
            [{"boothId": "likelion", "validDate": null, "tokenSha256": "%s"},
             {"boothId": "photo", "validDate": "2026-10-01", "tokenSha256": "%s"}]
            """.formatted(hashA, hashB)
        ));
        revisions.publish(withStamps, "release-bot");

        CatalogManifest exported = exports.export(withStamps).manifest();
        assertThat(exported.stampBooths()).extracting(CatalogManifest.StampBooth::id).containsExactly("likelion", "photo");
        assertThat(exported.stampBoothTokens()).extracting(CatalogManifest.StampBoothToken::tokenSha256)
            .containsExactly(hashA, hashB);
        assertThat(exports.export(importExportedManifest(exported)).manifest().stampBoothTokens())
            .isEqualTo(exported.stampBoothTokens());

        UUID rollback = revisions.rollback(first, withStamps, "incident-bot");
        assertThat(exports.export(rollback).manifest().stampBooths()).isEmpty();
        UUID restored = revisions.rollback(withStamps, rollback, "incident-bot");
        assertThat(exports.export(restored).manifest().stampBoothTokens()).hasSize(2);
    }

    @Test
    void rejectsBoothStampsThatBreakTheTokenRules() throws IOException {
        String base = manifestJson("qr-stamp-rules", "/assets/maps/overview-v1.png", currentPublishedRevision());
        String booths = """
            [{"id": "likelion", "name": "멋사 부스", "sortOrder": 1}]
            """;
        String hash = "c".repeat(64);
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("stampBoothTokens references an unknown booth", new String[] {booths,
            "[{\"boothId\": \"nobody\", \"validDate\": null, \"tokenSha256\": \"" + hash + "\"}]"});
        cases.put("64 lowercase hex characters", new String[] {booths,
            "[{\"boothId\": \"likelion\", \"validDate\": null, \"tokenSha256\": \"" + hash.toUpperCase() + "\"}]"});
        cases.put("Every stamp booth needs a token", new String[] {booths, "[]"});
        cases.put("validDate must be a festival day", new String[] {booths,
            "[{\"boothId\": \"likelion\", \"validDate\": \"2026-12-25\", \"tokenSha256\": \"" + hash + "\"}]"});
        cases.put("either one all-day token or dated tokens", new String[] {booths,
            "[{\"boothId\": \"likelion\", \"validDate\": null, \"tokenSha256\": \"" + hash + "\"},"
                + " {\"boothId\": \"likelion\", \"validDate\": \"2026-10-01\", \"tokenSha256\": \"" + "d".repeat(64) + "\"}]"});
        cases.put("Duplicate stampBoothTokens.tokenSha256", new String[] {
            "[{\"id\": \"likelion\", \"name\": \"멋사 부스\", \"sortOrder\": 1},"
                + " {\"id\": \"photo\", \"name\": \"포토부스\", \"sortOrder\": 2}]",
            "[{\"boothId\": \"likelion\", \"validDate\": null, \"tokenSha256\": \"" + hash + "\"},"
                + " {\"boothId\": \"photo\", \"validDate\": null, \"tokenSha256\": \"" + hash + "\"}]"});

        for (Map.Entry<String, String[]> entry : cases.entrySet()) {
            String json = withStampBooths(base, entry.getValue()[0], entry.getValue()[1]);
            assertThatThrownBy(() -> importJson(json))
                .as(entry.getKey())
                .isInstanceOf(CatalogCliException.class)
                .hasMessageContaining(entry.getKey());
        }
    }

    private static String withStampBooths(String manifestJson, String booths, String tokens) {
        int end = manifestJson.lastIndexOf('}');
        return manifestJson.substring(0, end)
            + ", \"stampBooths\": " + booths + ", \"stampBoothTokens\": " + tokens + "}";
    }

    private UUID importExportedManifest(CatalogManifest manifest) throws IOException {
        Path file = tempDir.resolve(UUID.randomUUID() + ".json");
        tools.jackson.databind.json.JsonMapper.builder()
            .findAndAddModules()
            .build()
            .writeValue(file.toFile(), manifest);
        return revisions.importManifest(file, "release-bot");
    }

    private UUID baseRevision(UUID revisionId) {
        return jdbc.queryForObject(
            "SELECT base_revision_id FROM festival_revisions WHERE id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId), UUID.class
        );
    }

    @Test
    void importsACompleteManifestAsADraftAndRecordsImportAudit() throws IOException {
        UUID revisionId = importManifest("qr-a", "/assets/maps/overview-v1.png");

        assertThat(revisionState(revisionId)).isEqualTo("draft");
        assertThat(auditActions(revisionId)).containsExactly("IMPORT");
        assertThat(revisionCount()).isEqualTo(2);
        for (String table : performanceTables()) {
            assertThat(rowCount(table, revisionId)).as(table).isPositive();
        }
    }

    @Test
    void importsAnExplicitlyEmptyPerformanceCatalog() throws IOException {
        Path manifest = tempDir.resolve("empty-performance.json");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules();
        com.fasterxml.jackson.databind.node.ObjectNode json = (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(manifestJson("qr-empty", "/assets/maps/overview-v1.png"));
        for (String field : new String[] {
            "artists", "artistTranslations", "artistLinks", "artistLinkTranslations",
            "artistSongs", "artistSongTranslations", "performances", "performanceTranslations",
            "performanceArtists", "prohibitedItems", "prohibitedItemTranslations", "prohibitedMessages"
        }) {
            json.putArray(field);
        }
        json.remove("timetableConfig");
        Files.writeString(manifest, mapper.writeValueAsString(json));

        UUID revisionId = revisions.importManifest(manifest, "release-bot");

        assertThat(revisionState(revisionId)).isEqualTo("draft");
        for (String table : performanceTables()) {
            assertThat(rowCount(table, revisionId)).as(table).isZero();
        }
    }

    @Test
    void validatesStoredPerformanceCatalogAndRecordsAudit() throws IOException {
        UUID revisionId = importManifest("qr-validate", "/assets/maps/overview-v1.png");

        revisions.validateRevision(revisionId, "validator-bot");

        assertThat(auditActions(revisionId)).containsExactly("IMPORT", "VALIDATE");
    }

    @Test
    void rejectsStoredCatalogWithoutKoreanPerformanceTranslation() throws IOException {
        UUID revisionId = importManifest("qr-invalid", "/assets/maps/overview-v1.png");
        jdbc.update(
            "DELETE FROM performance_translations WHERE festival_revision_id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId)
        );

        assertThatThrownBy(() -> revisions.validateRevision(revisionId, "validator-bot"))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("performances")
            .hasCauseInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class);

        assertThat(auditActions(revisionId)).containsExactly("IMPORT");
    }

    @Test
    void performanceRevisionValidatorRejectsIncompleteOrUnsafeStoredRows() throws IOException {
        UUID missingArtistKo = importManifest("qr-db-artist", "/assets/maps/overview-v1.png");
        deleteRevisionRows("artist_translations", missingArtistKo);
        assertThatThrownBy(() -> performanceRevisions.validate(missingArtistKo))
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class)
            .hasMessageContaining("artists");

        UUID missingLinkKo = importManifest("qr-db-link", "/assets/maps/overview-v1.png");
        deleteRevisionRows("artist_link_translations", missingLinkKo);
        assertThatThrownBy(() -> performanceRevisions.validate(missingLinkKo))
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class)
            .hasMessageContaining("artist_links");

        UUID missingSongKo = importManifest("qr-db-song", "/assets/maps/overview-v1.png");
        deleteRevisionRows("artist_song_translations", missingSongKo);
        assertThatThrownBy(() -> performanceRevisions.validate(missingSongKo))
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class)
            .hasMessageContaining("artist_songs");

        UUID missingItemKo = importManifest("qr-db-item", "/assets/maps/overview-v1.png");
        deleteRevisionRows("prohibited_item_translations", missingItemKo);
        assertThatThrownBy(() -> performanceRevisions.validate(missingItemKo))
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class)
            .hasMessageContaining("prohibited_items");

        UUID unsupportedLocale = importManifest("qr-db-locale", "/assets/maps/overview-v1.png");
        jdbc.update("""
            UPDATE artist_translations SET locale = 'fr'
            WHERE festival_revision_id = :revisionId
            """, new MapSqlParameterSource("revisionId", unsupportedLocale));
        assertThatThrownBy(() -> performanceRevisions.validate(unsupportedLocale))
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class)
            .hasMessageContaining("unsupported locale");

        UUID invalidImage = importManifest("qr-db-image", "/assets/maps/overview-v1.png");
        jdbc.update("""
            UPDATE artists SET image_url = 'bad uri'
            WHERE festival_revision_id = :revisionId
            """, new MapSqlParameterSource("revisionId", invalidImage));
        assertThatThrownBy(() -> performanceRevisions.validate(invalidImage))
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class)
            .hasMessageContaining("valid URI");
    }

    @Test
    void publishRevalidationLeavesCurrentPublicationUntouchedOnPerformanceFailure() throws IOException {
        UUID revisionId = importManifest("qr-invalid-publish", "/assets/maps/overview-v1.png");
        jdbc.update(
            "DELETE FROM performance_translations WHERE festival_revision_id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId)
        );

        assertThatThrownBy(() -> revisions.publish(revisionId, "release-bot"))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("performances")
            .hasCauseInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class);

        assertThat(revisionState(INITIAL_REVISION_ID)).isEqualTo("published");
        assertThat(revisionState(revisionId)).isEqualTo("draft");
        assertThat(auditActions(revisionId)).containsExactly("IMPORT");
    }

    @Test
    void rollsBackEveryPerformanceTableWithStableValues() throws IOException {
        UUID source = importManifest("qr-source", "/assets/maps/overview-v1.png");
        revisions.publish(source, "release-bot");
        UUID replacement = importManifest("qr-replacement", "/assets/maps/overview-v1.png");
        revisions.publish(replacement, "release-bot");
        RollbackCatalogSnapshot sourceBefore = rollbackCatalogSnapshot(source);

        UUID rollback = revisions.rollback(source, currentPublishedRevision(), "incident-bot");
        RollbackCatalogSnapshot sourceAfter = rollbackCatalogSnapshot(source);
        RollbackCatalogSnapshot target = rollbackCatalogSnapshot(rollback);

        for (String table : performanceTables()) {
            assertThat(rowCount(table, rollback)).as(table).isEqualTo(rowCount(table, source));
        }
        assertThat(sourceAfter.revisionId()).isEqualTo(source);
        assertThat(sourceAfter.performanceTables()).isEqualTo(sourceBefore.performanceTables());
        assertThat(sourceAfter.festivalDays()).isEqualTo(sourceBefore.festivalDays());
        assertThat(target.revisionId()).isEqualTo(rollback).isNotEqualTo(source);
        assertThat(target.performanceTables()).isEqualTo(sourceBefore.performanceTables());
        assertThat(target.festivalDays()).isEqualTo(sourceBefore.festivalDays());
        assertThat(jdbc.queryForMap(
            """
            SELECT id, festival_date, starts_at, ends_at
            FROM performances WHERE festival_revision_id = :revisionId
            """, new MapSqlParameterSource("revisionId", rollback)
        )).containsEntry("id", "performance-one");
        assertThat(jdbc.queryForMap(
            """
            SELECT artist_id, sort_order, url
            FROM artist_songs WHERE festival_revision_id = :revisionId
            """, new MapSqlParameterSource("revisionId", rollback)
        )).containsEntry("url", "https://example.test/song");
        assertThat(auditActions(rollback)).containsExactly("ROLLBACK", "PUBLISH");
    }

    @Test
    void rollsBackWholeImportWhenPerformanceInsertFails() throws IOException {
        assertForcedInsertFailureIsAtomic("performances");
    }

    @Test
    void rollsBackWholeImportWhenLateProhibitedMessageInsertFails() throws IOException {
        assertForcedInsertFailureIsAtomic("prohibited_messages");
    }

    @Test
    void publishesAtomicallyAndArchivesThePreviousRevision() throws IOException {
        UUID revisionId = importManifest("qr-a", "/assets/maps/overview-v1.png");

        revisions.publish(revisionId, "release-bot");

        assertThat(revisionState(revisionId)).isEqualTo("published");
        assertThat(revisionState(INITIAL_REVISION_ID)).isEqualTo("archived");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM festival_revisions WHERE festival_id = :festivalId AND state = 'published'",
            new MapSqlParameterSource("festivalId", FESTIVAL_ID), Long.class
        )).isEqualTo(1);
        assertThat(auditActions(revisionId)).containsExactly("IMPORT", "PUBLISH");
        assertThat(jdbc.queryForObject(
            "SELECT actor FROM catalog_revision_audit WHERE revision_id = :revisionId AND action = 'PUBLISH'",
            new MapSqlParameterSource("revisionId", revisionId), String.class
        )).isEqualTo("release-bot");
        assertThat(snapshots.loadPublished().context().revisionId()).isEqualTo(revisionId);
    }

    @Test
    void rollsBackAnArchivedRevisionWithItsMapVersionAndGuideQrButWithoutCrowdingState() throws IOException {
        UUID firstRevision = importManifest("qr-a", "/assets/maps/overview-v1.png");
        revisions.publish(firstRevision, "release-bot");
        insertCrowdingState(firstRevision);

        UUID newerRevision = importManifest("qr-b", "/assets/maps/overview-v1.png");
        revisions.publish(newerRevision, "release-bot");

        UUID rollbackRevision = revisions.rollback(firstRevision, currentPublishedRevision(), "incident-bot");

        assertThat(revisionState(rollbackRevision)).isEqualTo("published");
        assertThat(revisionState(firstRevision)).isEqualTo("archived");
        assertThat(revisionState(newerRevision)).isEqualTo("archived");
        assertThat(jdbc.queryForObject(
            "SELECT current_version FROM maps WHERE festival_revision_id = :revisionId AND id = 'map-overview'",
            new MapSqlParameterSource("revisionId", rollbackRevision), String.class
        )).isEqualTo("overview-v1");
        assertThat(jdbc.queryForObject(
            "SELECT qr_value FROM stamp_guide_revisions WHERE festival_revision_id = :revisionId AND id = 1",
            new MapSqlParameterSource("revisionId", rollbackRevision), String.class
        )).isEqualTo("qr-a");
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*)
            FROM crowding_state c
            JOIN festival_days d ON d.id = c.festival_day_id
            WHERE d.festival_revision_id = :revisionId
            """,
            new MapSqlParameterSource("revisionId", rollbackRevision), Long.class
        )).isZero();
        assertThat(auditActions(rollbackRevision)).containsExactly("ROLLBACK", "PUBLISH");
    }

    @Test
    void rejectsPublishingAnOlderDraftAndLeavesTheNewerPublishedRevisionInPlace() throws IOException {
        UUID olderRevision = importManifest("qr-a", "/assets/maps/overview-v1.png");
        UUID newerRevision = importManifest("qr-b", "/assets/maps/overview-v1.png");
        revisions.publish(newerRevision, "release-bot");

        assertThatThrownBy(() -> revisions.publish(olderRevision, "release-bot"))
            .isInstanceOf(CatalogCliException.class);

        assertThat(revisionState(newerRevision)).isEqualTo("published");
        assertThat(revisionState(olderRevision)).isEqualTo("draft");
        assertThat(auditActions(olderRevision)).containsExactly("IMPORT");
    }

    @Test
    void rejectsMalformedOrPartialManifestWithoutInsertingARevisionOrAudit() throws IOException {
        Path malformed = tempDir.resolve("malformed.json");
        Files.writeString(malformed, "{\"festivalId\":\"" + FESTIVAL_ID);

        assertThatThrownBy(() -> revisions.importManifest(malformed, "release-bot"))
            .isInstanceOf(CatalogCliException.class);

        Path partial = tempDir.resolve("partial.json");
        Files.writeString(partial, "{\"festivalId\":\"" + FESTIVAL_ID + "\",\"spaces\":[]}");

        assertThatThrownBy(() -> revisions.importManifest(partial, "release-bot"))
            .isInstanceOf(CatalogCliException.class);

        assertThat(revisionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_revision_audit", Map.of(), Long.class))
            .isZero();
    }

    @Test
    void rollsBackRowsWhenCrossRevisionValidationFailsAfterInsertion() throws IOException {
        UUID publishedRevision = importManifest("qr-a", "/assets/maps/overview-v1.png");
        revisions.publish(publishedRevision, "release-bot");
        long auditsBefore = auditCount();

        assertThatThrownBy(() -> importManifest("qr-drift", "/assets/maps/different.png"))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("different image data")
            .hasCauseInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class);

        assertThat(revisionCount()).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(auditsBefore);
        assertThat(revisionState(publishedRevision)).isEqualTo("published");
    }

    private UUID currentPublishedRevision() {
        return jdbc.query(
            "SELECT id FROM festival_revisions WHERE festival_id = :festivalId AND state = 'published'",
            new MapSqlParameterSource("festivalId", FESTIVAL_ID),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)
        ).stream().findFirst().orElse(null);
    }

    private UUID importManifest(String qrValue, String imageUrl) throws IOException {
        return importManifest(qrValue, imageUrl, currentPublishedRevision());
    }

    private UUID importManifest(String qrValue, String imageUrl, UUID baseline) throws IOException {
        Path manifest = tempDir.resolve(UUID.randomUUID() + ".json");
        Files.writeString(manifest, manifestJson(qrValue, imageUrl, baseline));
        return revisions.importManifest(manifest, "release-bot");
    }

    private void assertForcedInsertFailureIsAtomic(String table) throws IOException {
        String trigger = "test_fail_" + table;
        jdbc.getJdbcTemplate().execute("""
            CREATE OR REPLACE FUNCTION test_fail_catalog_insert()
            RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              RAISE EXCEPTION 'forced catalog insert failure';
            END;
            $$
            """);
        jdbc.getJdbcTemplate().execute(
            "CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION test_fail_catalog_insert()"
        );
        try {
            assertThatThrownBy(() -> importManifest("qr-failure", "/assets/maps/overview-v1.png"))
                .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            jdbc.getJdbcTemplate().execute("DROP FUNCTION IF EXISTS test_fail_catalog_insert()");
        }

        assertThat(revisionCount()).isEqualTo(1);
        assertThat(auditCount()).isZero();
        for (String childTable : performanceTables()) {
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + childTable
                    + " WHERE festival_revision_id <> :initialRevisionId",
                new MapSqlParameterSource("initialRevisionId", INITIAL_REVISION_ID), Long.class
            )).as(childTable).isZero();
        }
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM festival_days WHERE festival_revision_id <> :initialRevisionId",
            new MapSqlParameterSource("initialRevisionId", INITIAL_REVISION_ID), Long.class
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM maps WHERE festival_revision_id <> :initialRevisionId",
            new MapSqlParameterSource("initialRevisionId", INITIAL_REVISION_ID), Long.class
        )).isZero();
    }

    private String manifestJson(String qrValue, String imageUrl) {
        return manifestJson(qrValue, imageUrl, currentPublishedRevision());
    }

    private String manifestJson(String qrValue, String imageUrl, UUID baseline) {
        return """
            {
              "festivalId": "%s",
              "baselineRevisionId": %s,
              "festivalDays": [
                {
                  "festivalDate": "2026-10-01",
                  "opensAt": "2026-10-01T09:00:00+09:00",
                  "closesAt": "2026-10-01T18:00:00+09:00"
                }
              ],
              "spaces": [
                {
                  "id": "space-booth", "category": "BOOTH",
                  "imageUrl": "/assets/spaces/booth.png", "imageWidth": 800, "imageHeight": 600
                }
              ],
              "spaceTranslations": [
                {
                  "spaceId": "space-booth", "locale": "ko", "name": "테스트 부스",
                  "imageAlt": "부스 사진", "locationText": "학생회관 앞", "operatorText": null,
                  "hoursText": null, "descriptionText": null, "experienceText": null,
                  "contactLabel": null, "contactUrl": null
                }
              ],
              "spaceSortOrders": [
                {"locale": "ko", "spaceId": "space-booth", "sortRank": 1}
              ],
              "spaceEvents": [],
              "spaceMenuItems": [],
              "places": [
                {"id": "place-booth", "kind": "SPACE", "spaceId": "space-booth"}
              ],
              "placeTranslations": [
                {
                  "placeId": "place-booth", "locale": "ko", "name": "테스트 부스",
                  "locationText": "학생회관 앞", "hoursText": null,
                  "descriptionText": null, "usageText": null
                }
              ],
              "maps": [
                {
                  "id": "map-overview",
                  "kind": "OVERVIEW",
                  "sortRank": 1,
                  "currentVersion": "overview-v1"
                },
                {
                  "id": "map-area",
                  "kind": "AREA",
                  "sortRank": 2,
                  "currentVersion": "area-v1"
                }
              ],
              "mapTranslations": [
                {"mapId": "map-overview", "locale": "ko", "name": "전체 지도"},
                {"mapId": "map-area", "locale": "ko", "name": "구역 지도"}
              ],
              "mapAssets": [
                {
                  "mapId": "map-overview",
                  "version": "overview-v1",
                  "imageUrl": "%s",
                  "imageAlt": "전체 지도",
                  "imageWidth": 1000,
                  "imageHeight": 600
                },
                {
                  "mapId": "map-area",
                  "version": "area-v1",
                  "imageUrl": "/assets/maps/area-v1.png",
                  "imageAlt": "구역 지도",
                  "imageWidth": 1200,
                  "imageHeight": 700
                }
              ],
              "mapAreas": [
                {"id": "area-booths", "targetMapId": "map-area"}
              ],
              "mapPins": [
                {
                  "mapId": "map-overview", "mapVersion": "overview-v1", "id": "pin-area",
                  "category": "area", "filterGroup": null, "x": 0.25, "y": 0.75,
                  "placeId": null, "areaId": "area-booths"
                },
                {
                  "mapId": "map-area", "mapVersion": "area-v1", "id": "pin-booth",
                  "category": "booth", "filterGroup": "PHOTO_BOOTH", "x": 0.5, "y": 0.5,
                  "placeId": "place-booth", "areaId": null
                }
              ],
              "mapPinTranslations": [
                {
                  "mapId": "map-overview", "mapVersion": "overview-v1", "pinId": "pin-area",
                  "locale": "ko", "label": "부스 구역"
                },
                {
                  "mapId": "map-area", "mapVersion": "area-v1", "pinId": "pin-booth",
                  "locale": "ko", "label": "테스트 부스"
                }
              ],
              "mapPinFilterGroupTranslations": [
                {"filterGroup": "PHOTO_BOOTH", "locale": "ko", "label": "포토부스"}
              ],
              "spaceMapTargets": [
                {
                  "spaceId": "space-booth", "mapId": "map-area", "mapVersion": "area-v1",
                  "pinId": "pin-booth", "placeId": "place-booth"
                }
              ],
              "artists": [
                {
                  "id": "artist-one", "category": "ARTIST",
                  "imageUrl": "/assets/artists/one.png", "imageWidth": 800, "imageHeight": 800
                }
              ],
              "artistTranslations": [
                {
                  "artistId": "artist-one", "locale": "ko", "name": "가수",
                  "imageAlt": "가수 사진", "introduction": "소개"
                }
              ],
              "artistLinks": [
                {"artistId": "artist-one", "sortOrder": 1, "url": "https://example.test/artist"}
              ],
              "artistLinkTranslations": [
                {"artistId": "artist-one", "sortOrder": 1, "locale": "ko", "label": "공식 링크"}
              ],
              "artistSongs": [
                {"artistId": "artist-one", "sortOrder": 1, "url": "https://example.test/song"}
              ],
              "artistSongTranslations": [
                {"artistId": "artist-one", "sortOrder": 1, "locale": "ko", "title": "대표곡"}
              ],
              "performances": [
                {
                  "id": "performance-one", "festivalDate": "2026-10-01",
                  "startsAt": "2026-10-01T17:00:00+09:00",
                  "endsAt": "2026-10-01T18:00:00+09:00"
                }
              ],
              "performanceTranslations": [
                {"performanceId": "performance-one", "locale": "ko", "title": "공연", "description": null}
              ],
              "performanceArtists": [
                {"performanceId": "performance-one", "artistId": "artist-one", "displayOrder": 1}
              ],
              "timetableConfig": {"axisStartTime": "17:00:00", "axisEndTime": "22:00:00"},
              "prohibitedItems": [
                {"id": "prohibited-one", "sortOrder": 1}
              ],
              "prohibitedItemTranslations": [
                {"itemId": "prohibited-one", "locale": "ko", "label": "반입 금지"}
              ],
              "prohibitedMessages": [
                {"locale": "ko", "message": "반입할 수 없습니다"}
              ],
              "ticketGuide": {
                "unitPriceAmount": null,
                "mapId": null,
                "placeId": null,
                "pinId": null,
                "mapVersion": null,
                "instructions": ["티켓 안내"],
                "festivalStartDate": null,
                "festivalEndDate": null,
                "dailyTransferOpenTime": null,
                "dailyTransferCloseTime": null,
                "dailyPickupOpenTime": null,
                "dailyPickupCloseTime": null
              },
              "stampGuide": {
                "title": "스탬프투어",
                "dates": ["2026-10-01"],
                "instructions": ["스탬프 안내"],
                "rewardName": "기념품",
                "rewardLocationText": null,
                "rewardHoursText": null,
                "rewardNotice": "운영 안내",
                "qrValue": "%s"
              },
              "festivalLinks": [
                {"id": "notices", "kind": "UNIVERSITY_NOTICES", "url": "https://example.test/notices", "iconKey": null, "sortOrder": 1},
                {"id": "instagram", "kind": "OFFICIAL_CHANNEL", "url": "https://example.test/instagram", "iconKey": "instagram", "sortOrder": 1},
                {"id": "youtube", "kind": "OFFICIAL_CHANNEL", "url": "https://example.test/youtube", "iconKey": "youtube", "sortOrder": 2}
              ],
              "festivalLinkTranslations": [
                {"linkId": "notices", "locale": "ko", "label": "공지사항"},
                {"linkId": "instagram", "locale": "ko", "label": "Instagram"},
                {"linkId": "instagram", "locale": "en", "label": "Instagram"},
                {"linkId": "youtube", "locale": "ko", "label": "YouTube"}
              ]
            }
            """.formatted(FESTIVAL_ID, baseline == null ? "null" : "\"" + baseline + "\"", imageUrl, qrValue);
    }

    private void insertCrowdingState(UUID revisionId) {
        UUID festivalDayId = jdbc.queryForObject(
            "SELECT id FROM festival_days WHERE festival_revision_id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId), UUID.class
        );
        jdbc.update(
            """
            INSERT INTO crowding_state (operating_day, festival_day_id, level, updated_at)
            VALUES ('2026-10-01', :festivalDayId, 'CROWDED', :updatedAt)
            """,
            new MapSqlParameterSource()
                .addValue("festivalDayId", festivalDayId)
                .addValue("updatedAt", OffsetDateTime.parse("2026-10-01T00:00:00Z"))
        );
    }

    private String revisionState(UUID revisionId) {
        return jdbc.queryForObject(
            "SELECT state FROM festival_revisions WHERE id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId), String.class
        );
    }

    private long revisionCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM festival_revisions WHERE festival_id = :festivalId",
            new MapSqlParameterSource("festivalId", FESTIVAL_ID), Long.class
        );
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM catalog_revision_audit", Map.of(), Long.class);
    }

    private long rowCount(String table, UUID revisionId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE festival_revision_id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId), Long.class
        );
    }

    private RollbackCatalogSnapshot rollbackCatalogSnapshot(UUID revisionId) {
        Map<String, List<StableRow>> tables = new LinkedHashMap<>();
        tables.put("artists", stableRows(
            revisionId, "artists", "id",
            "id", "category", "image_url", "image_width", "image_height"
        ));
        tables.put("artist_translations", stableRows(
            revisionId, "artist_translations", "artist_id, locale",
            "artist_id", "locale", "name", "image_alt", "introduction"
        ));
        tables.put("artist_links", stableRows(
            revisionId, "artist_links", "artist_id, sort_order",
            "artist_id", "sort_order", "url"
        ));
        tables.put("artist_link_translations", stableRows(
            revisionId, "artist_link_translations", "artist_id, sort_order, locale",
            "artist_id", "sort_order", "locale", "label"
        ));
        tables.put("artist_songs", stableRows(
            revisionId, "artist_songs", "artist_id, sort_order",
            "artist_id", "sort_order", "url"
        ));
        tables.put("artist_song_translations", stableRows(
            revisionId, "artist_song_translations", "artist_id, sort_order, locale",
            "artist_id", "sort_order", "locale", "title"
        ));
        tables.put("performances", stableRows(
            revisionId, "performances", "id",
            "id", "festival_date", "starts_at", "ends_at"
        ));
        tables.put("performance_translations", stableRows(
            revisionId, "performance_translations", "performance_id, locale",
            "performance_id", "locale", "title", "description"
        ));
        tables.put("performance_artists", stableRows(
            revisionId, "performance_artists", "performance_id, display_order, artist_id",
            "performance_id", "artist_id", "display_order"
        ));
        tables.put("timetable_configs", stableRows(
            revisionId, "timetable_configs", "festival_revision_id",
            "axis_start_time", "axis_end_time"
        ));
        tables.put("prohibited_items", stableRows(
            revisionId, "prohibited_items", "id", "id", "sort_order"
        ));
        tables.put("prohibited_item_translations", stableRows(
            revisionId, "prohibited_item_translations", "item_id, locale",
            "item_id", "locale", "label"
        ));
        tables.put("prohibited_messages", stableRows(
            revisionId, "prohibited_messages", "locale", "locale", "message"
        ));
        List<StableRow> festivalDays = stableRows(
            revisionId, "festival_days", "festival_date",
            "festival_date", "opens_at", "closes_at"
        );
        return new RollbackCatalogSnapshot(
            revisionId, Collections.unmodifiableMap(tables), festivalDays
        );
    }

    private List<StableRow> stableRows(
        UUID revisionId,
        String table,
        String orderBy,
        String... columns
    ) {
        String sql = "SELECT festival_revision_id, " + String.join(", ", columns)
            + " FROM " + table
            + " WHERE festival_revision_id = :revisionId ORDER BY " + orderBy;
        return jdbc.query(
            sql,
            new MapSqlParameterSource("revisionId", revisionId),
            (resultSet, rowNumber) -> {
                assertThat(resultSet.getObject("festival_revision_id", UUID.class))
                    .as(table + " revision id")
                    .isEqualTo(revisionId);
                List<Object> values = new ArrayList<>(columns.length);
                for (String column : columns) {
                    values.add(stableValue(resultSet, column));
                }
                return new StableRow(Collections.unmodifiableList(values));
            }
        );
    }

    private Object stableValue(ResultSet resultSet, String column) throws SQLException {
        return switch (column) {
            case "festival_date" -> resultSet.getObject(column, LocalDate.class);
            case "axis_start_time", "axis_end_time" -> resultSet.getObject(column, LocalTime.class);
            case "starts_at", "ends_at", "opens_at", "closes_at" ->
                resultSet.getObject(column, OffsetDateTime.class).toInstant();
            default -> resultSet.getObject(column);
        };
    }

    private void deleteRevisionRows(String table, UUID revisionId) {
        jdbc.update(
            "DELETE FROM " + table + " WHERE festival_revision_id = :revisionId",
            new MapSqlParameterSource("revisionId", revisionId)
        );
    }

    private java.util.List<String> performanceTables() {
        return java.util.List.of(
            "artists", "artist_translations", "artist_links", "artist_link_translations",
            "artist_songs", "artist_song_translations", "performances",
            "performance_translations", "performance_artists", "timetable_configs",
            "prohibited_items", "prohibited_item_translations", "prohibited_messages"
        );
    }

    private java.util.List<String> auditActions(UUID revisionId) {
        return jdbc.query(
            "SELECT action FROM catalog_revision_audit WHERE revision_id = :revisionId ORDER BY id",
            new MapSqlParameterSource("revisionId", revisionId),
            (resultSet, rowNumber) -> resultSet.getString("action")
        );
    }

    private record RollbackCatalogSnapshot(
        UUID revisionId,
        Map<String, List<StableRow>> performanceTables,
        List<StableRow> festivalDays
    ) {}

    private record StableRow(List<Object> values) {}
}

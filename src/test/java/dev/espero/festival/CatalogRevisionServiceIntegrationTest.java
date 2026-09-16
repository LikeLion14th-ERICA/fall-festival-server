package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.persistence.CatalogSnapshotStore;
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class);

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
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class);

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

        UUID rollback = revisions.rollback(source, "incident-bot");
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

        UUID rollbackRevision = revisions.rollback(firstRevision, "incident-bot");

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
            .isInstanceOf(dev.espero.festival.persistence.CatalogIntegrityException.class)
            .hasMessageContaining("different image data");

        assertThat(revisionCount()).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(auditsBefore);
        assertThat(revisionState(publishedRevision)).isEqualTo("published");
    }

    private UUID importManifest(String qrValue, String imageUrl) throws IOException {
        Path manifest = tempDir.resolve(UUID.randomUUID() + ".json");
        Files.writeString(manifest, manifestJson(qrValue, imageUrl));
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
        return """
            {
              "festivalId": "%s",
              "festivalDays": [
                {
                  "festivalDate": "2026-10-01",
                  "opensAt": "2026-10-01T09:00:00+09:00",
                  "closesAt": "2026-10-01T18:00:00+09:00"
                }
              ],
              "spaces": [],
              "spaceTranslations": [],
              "spaceSortOrders": [],
              "spaceEvents": [],
              "spaceMenuItems": [],
              "places": [],
              "placeTranslations": [],
              "maps": [
                {
                  "id": "map-overview",
                  "kind": "OVERVIEW",
                  "sortRank": 1,
                  "currentVersion": "overview-v1"
                }
              ],
              "mapTranslations": [
                {"mapId": "map-overview", "locale": "ko", "name": "전체 지도"}
              ],
              "mapAssets": [
                {
                  "mapId": "map-overview",
                  "version": "overview-v1",
                  "imageUrl": "%s",
                  "imageAlt": "전체 지도",
                  "imageWidth": 1000,
                  "imageHeight": 600
                }
              ],
              "mapAreas": [],
              "mapPins": [],
              "mapPinTranslations": [],
              "mapPinFilterGroupTranslations": [],
              "spaceMapTargets": [],
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
                "accountBankName": null,
                "accountNumber": null,
                "accountHolder": null,
                "transferLinkLabel": null,
                "transferLinkUrl": null,
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
              }
            }
            """.formatted(FESTIVAL_ID, imageUrl, qrValue);
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

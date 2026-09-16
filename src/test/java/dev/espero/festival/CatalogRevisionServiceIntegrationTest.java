package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.persistence.CatalogSnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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

    private java.util.List<String> auditActions(UUID revisionId) {
        return jdbc.query(
            "SELECT action FROM catalog_revision_audit WHERE revision_id = :revisionId ORDER BY id",
            new MapSqlParameterSource("revisionId", revisionId),
            (resultSet, rowNumber) -> resultSet.getString("action")
        );
    }
}

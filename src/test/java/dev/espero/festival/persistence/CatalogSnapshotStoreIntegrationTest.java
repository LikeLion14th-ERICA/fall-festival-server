package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.domain.CatalogSnapshot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises V7 constraints and the read path against PostgreSQL, not mocks. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class CatalogSnapshotStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CatalogSnapshotStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void loadsTheInitiallyPublishedButEmptyCatalog() {
        CatalogSnapshot snapshot = store.loadPublished();

        assertThat(snapshot.context().revision()).isEqualTo(1);
        assertThat(snapshot.spaces()).isEmpty();
        assertThat(snapshot.maps()).isEmpty();
        assertThat(snapshot.places()).isEmpty();
        assertThat(snapshot.ticketGuideConfig()).isNotNull();
        assertThat(snapshot.ticketMapTarget()).isNull();
    }

    @Test
    @Transactional
    void loadsACanonicalSpaceAndTicketPlacePinTarget() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);

        CatalogSnapshot snapshot = store.loadPublished();

        assertThat(snapshot.findSpace("space-test")).hasValueSatisfying(space -> {
            assertThat(space.experience()).isEqualTo("테스트 체험");
            assertThat(space.mapTarget()).isEqualTo(new CatalogSnapshot.MapTarget(
                "map-area", "place-test", "pin-test", "map-v1"
            ));
        });
        assertThat(snapshot.pinsFor("map-area", "map-v1")).singleElement().satisfies(pin -> {
            assertThat(pin.target()).isEqualTo(new CatalogSnapshot.PinTarget("PLACE", "place-test"));
        });
        assertThat(snapshot.ticketGuideConfig()).isNotNull();
        assertThat(snapshot.ticketMapTarget()).isEqualTo(new CatalogSnapshot.MapTarget(
            "map-area", "place-test", "pin-test", "map-v1"
        ));
    }

    @Test
    @Transactional
    void rejectsAPublishedSpaceWhenItsKoreanTranslationIsMissing() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            DELETE FROM space_translations
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test' AND locale = 'ko'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Published space is missing a Korean translation");
    }

    @Test
    @Transactional
    void rejectsAPublishedSpaceWhenItsKoreanSortRankIsMissing() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            DELETE FROM space_sort_orders
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test' AND locale = 'ko'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Published space is missing a Korean sort rank");
    }

    @Test
    @Transactional
    void loadsKoreanSpaceOrderWithoutReusingIndependentEnglishRanks() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            UPDATE space_sort_orders
            SET sort_rank = 2
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test' AND locale = 'ko'
            """, parameters(revisionId));
        jdbc.update("""
            INSERT INTO spaces (festival_revision_id, id, category, image_url, image_width, image_height)
            VALUES (:revisionId, 'space-second', 'BOOTH', '/assets/spaces/second.png', 100, 100)
            """, parameters(revisionId));
        jdbc.update("""
            INSERT INTO space_translations (
                festival_revision_id, space_id, locale, name, image_alt, location_text, experience_text
            ) VALUES
                (:revisionId, 'space-second', 'ko', '두 번째 부스', '두 번째 부스', '두 번째 위치', '두 번째 체험'),
                (:revisionId, 'space-test', 'en', 'Test Booth', 'Test Booth', 'Test location', 'Test experience'),
                (:revisionId, 'space-second', 'en', 'Second Booth', 'Second Booth', 'Second location', 'Second experience')
            """, parameters(revisionId));
        jdbc.update("""
            INSERT INTO space_sort_orders (festival_revision_id, locale, space_id, sort_rank)
            VALUES
                (:revisionId, 'ko', 'space-second', 1),
                (:revisionId, 'en', 'space-test', 1),
                (:revisionId, 'en', 'space-second', 2)
            """, parameters(revisionId));

        CatalogSnapshot snapshot = store.loadPublished();
        List<String> koreanOrder = snapshot.spaces().stream().map(CatalogSnapshot.Space::id).toList();
        List<String> englishOrder = jdbc.query("""
            SELECT space_id
            FROM space_sort_orders
            WHERE festival_revision_id = :revisionId AND locale = 'en'
            ORDER BY sort_rank
            """, parameters(revisionId), (resultSet, rowNumber) -> resultSet.getString("space_id"));

        assertThat(koreanOrder).containsExactly("space-second", "space-test");
        assertThat(englishOrder).containsExactly("space-test", "space-second");
    }

    @Test
    @Transactional
    void rejectsASpaceMapTargetOnAnOverviewMap() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertOverviewPlacePin(revisionId, "pin-overview");
        jdbc.update("""
            UPDATE space_map_targets
            SET map_id = 'map-overview', map_version = 'overview-v1', pin_id = 'pin-overview'
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Space map target must point to an AREA map");
    }

    @Test
    @Transactional
    void allowsATicketMapTargetOnAnOverviewMap() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertOverviewPlacePin(revisionId, "pin-overview");
        jdbc.update("""
            UPDATE ticket_guide
            SET map_id = 'map-overview', place_id = 'place-test', pin_id = 'pin-overview', map_version = 'overview-v1'
            WHERE id = 1 AND festival_revision_id = :revisionId
            """, parameters(revisionId));

        assertThat(store.loadPublished().ticketMapTarget()).isEqualTo(new CatalogSnapshot.MapTarget(
            "map-overview", "place-test", "pin-overview", "overview-v1"
        ));
    }

    @Test
    @Transactional
    void rejectsCatalogIdsOutsideTheApiPatternAtSnapshotLoad() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'Map-Invalid', 'AREA', 3, 'invalid-v1')
            """, parameters(revisionId));
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'Map-Invalid', 'invalid-v1', '/assets/maps/invalid.png', '잘못된 ID 지도', 1000, 600)
            """, parameters(revisionId));
        jdbc.update("""
            INSERT INTO map_translations (festival_revision_id, map_id, locale, name)
            VALUES (:revisionId, 'Map-Invalid', 'ko', '잘못된 ID 지도')
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Map.id");
    }

    @Test
    @Transactional
    void rejectsASpaceImageUrlThatIsNotAUriReferenceAtSnapshotLoad() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            UPDATE spaces
            SET image_url = '/assets/space image.png'
            WHERE festival_revision_id = :revisionId AND id = 'space-test'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Space.image.url");
    }

    @Test
    @Transactional
    void rejectsASpaceImageUrlWithRawUnicodeAtSnapshotLoad() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            UPDATE spaces
            SET image_url = '/assets/지도.png'
            WHERE festival_revision_id = :revisionId AND id = 'space-test'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Space.image.url")
            .hasMessageContaining("ASCII URI characters");
    }

    @Test
    @Transactional
    void rejectsASpaceContactUrlThatIsNotAValidHttpsUriAtSnapshotLoad() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            UPDATE space_translations
            SET contact_label = '문의', contact_url = 'https://bad url'
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test' AND locale = 'ko'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Space.contact.url")
            .hasMessageContaining("valid URI reference");
    }

    @Test
    @Transactional
    void rejectsASpaceContactUrlWithRawUnicodeAtSnapshotLoad() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            UPDATE space_translations
            SET contact_label = '문의', contact_url = 'https://example.org/문의'
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test' AND locale = 'ko'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Space.contact.url")
            .hasMessageContaining("ASCII URI characters");
    }

    @Test
    @Transactional
    void rejectsATicketTransferUrlWithRawUnicodeAtSnapshotLoad() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            UPDATE ticket_guide
            SET transfer_link_label = '송금', transfer_link_url = 'https://example.org/문의'
            WHERE id = 1 AND festival_revision_id = :revisionId
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("TicketGuide.transferLink.url")
            .hasMessageContaining("ASCII URI characters");
    }

    @Test
    @Transactional
    void rejectsAMapImageUrlThatIsNotAUriReferenceAtSnapshotLoad() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            UPDATE map_asset_versions
            SET image_url = '/assets/map image.png'
            WHERE festival_revision_id = :revisionId AND map_id = 'map-area' AND version = 'map-v1'
            """, parameters(revisionId));

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Map.image.url");
    }

    @Test
    void rejectsAPartialTicketMapTarget() {
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE ticket_guide SET map_id = 'map-area' WHERE id = 1", Map.of()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAnOrphanedOrCrossRevisionSpaceTargetAtInsert() {
        UUID published = publishedRevisionId();
        MapSqlParameterSource archived = insertArchivedRevision(published);

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO space_map_targets (
                festival_revision_id, space_id, map_id, map_version, pin_id, place_id
            ) VALUES (:revisionId, 'space-test', 'map-area', 'map-v1', 'pin-test', 'place-test')
            """, archived))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAPlaceToSpaceMismatchForTheCanonicalSpaceTarget() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            VALUES (:revisionId, 'place-facility', 'FACILITY', NULL)
            """, parameters(revisionId));

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE space_map_targets
            SET place_id = 'place-facility'
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test'
            """, parameters(revisionId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAnAreaPinAsTheCanonicalSpaceTargetAtInsert() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertAreaPin(revisionId, "area-pin");

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE space_map_targets
            SET pin_id = 'area-pin'
            WHERE festival_revision_id = :revisionId AND space_id = 'space-test'
            """, parameters(revisionId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAnAreaPinAsTheTicketTargetAtInsert() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertAreaPin(revisionId, "area-pin");

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE ticket_guide
            SET pin_id = 'area-pin'
            WHERE id = 1 AND festival_revision_id = :revisionId
            """, parameters(revisionId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAStaleMapVersionForTheCanonicalSpaceTargetAtInsert() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertMapVersion(revisionId, "map-v2");
        jdbc.update("DELETE FROM space_map_targets WHERE festival_revision_id = :revisionId", parameters(revisionId));
        jdbc.update("UPDATE ticket_guide SET map_id = NULL, place_id = NULL, pin_id = NULL, map_version = NULL WHERE id = 1", Map.of());
        jdbc.update("""
            UPDATE maps SET current_version = 'map-v2'
            WHERE festival_revision_id = :revisionId AND id = 'map-area'
            """, parameters(revisionId));

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO space_map_targets (
                festival_revision_id, space_id, map_id, map_version, pin_id, place_id
            ) VALUES (:revisionId, 'space-test', 'map-area', 'map-v1', 'pin-test', 'place-test')
            """, parameters(revisionId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAStaleMapVersionForTheTicketTargetAtInsert() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertMapVersion(revisionId, "map-v2");
        jdbc.update("DELETE FROM space_map_targets WHERE festival_revision_id = :revisionId", parameters(revisionId));
        jdbc.update("UPDATE ticket_guide SET map_id = NULL, place_id = NULL, pin_id = NULL, map_version = NULL WHERE id = 1", Map.of());
        jdbc.update("""
            UPDATE maps SET current_version = 'map-v2'
            WHERE festival_revision_id = :revisionId AND id = 'map-area'
            """, parameters(revisionId));

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE ticket_guide
            SET map_id = 'map-area', place_id = 'place-test', pin_id = 'pin-test', map_version = 'map-v1'
            WHERE id = 1 AND festival_revision_id = :revisionId
            """, parameters(revisionId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAMapPinWithBothPlaceAndAreaTargetsAtInsert() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertAreaPin(revisionId, "area-pin");

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-area', 'map-v1', 'invalid-pin', 'area', 0.5, 0.5, 'place-test', 'area-route')
            """, parameters(revisionId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAMapPinWithoutAPlaceOrAreaTargetAtInsert() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-area', 'map-v1', 'invalid-pin', 'booth', 0.5, 0.5, NULL, NULL)
            """, parameters(revisionId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsATicketTargetWhosePlaceDoesNotMatchThePin() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        jdbc.update("""
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            VALUES (:revisionId, 'place-other', 'FACILITY', NULL)
            """, parameters(revisionId));

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE ticket_guide
            SET place_id = 'place-other'
            WHERE id = 1
            """, parameters(revisionId))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsReusingAMapVersionForDifferentAssetGeometry() {
        UUID publishedRevisionId = publishedRevisionId();
        insertCatalogFixture(publishedRevisionId);
        MapSqlParameterSource archived = insertArchivedRevision(publishedRevisionId);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-area', 'AREA', 1, 'map-v1')
            """, archived);
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-area', 'map-v1', '/assets/maps/different.png', '다른 테스트 지도', 1000, 600)
            """, archived);

        assertThatThrownBy(store::loadPublished).isInstanceOf(CatalogIntegrityException.class);
    }

    @Test
    @Transactional
    void allowsReusingAMapVersionWhenOnlyAlternativeTextChanges() {
        UUID publishedRevisionId = publishedRevisionId();
        insertCatalogFixture(publishedRevisionId);
        MapSqlParameterSource archived = insertArchivedRevision(publishedRevisionId);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-area', 'AREA', 1, 'map-v1')
            """, archived);
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-area', 'map-v1', '/assets/maps/map-v1.png', '바뀐 대체 텍스트', 1000, 600)
            """, archived);
        jdbc.update("""
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            VALUES (:revisionId, 'place-test', 'FACILITY', NULL)
            """, archived);
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-area', 'map-v1', 'pin-test', 'booth', 0.5, 0.25, 'place-test', NULL)
            """, archived);

        assertThatCode(store::loadPublished).doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void rejectsReusingAMapVersionWhenPinCoordinatesDrift() {
        UUID publishedRevisionId = publishedRevisionId();
        insertCatalogFixture(publishedRevisionId);
        MapSqlParameterSource archived = insertArchivedRevision(publishedRevisionId);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-area', 'AREA', 1, 'map-v1')
            """, archived);
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-area', 'map-v1', '/assets/maps/map-v1.png', '테스트 지도', 1000, 600)
            """, archived);
        jdbc.update("""
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            VALUES (:revisionId, 'place-test', 'FACILITY', NULL)
            """, archived);
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-area', 'map-v1', 'pin-test', 'booth', 0.75, 0.25, 'place-test', NULL)
            """, archived);

        assertThatThrownBy(store::loadPublished)
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("different pin geometry or targets across revisions");
    }

    @Test
    @Transactional
    void keepsCoordinateChangesScopedToTheirMapVersion() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);
        insertMapVersion(revisionId, "map-v2");
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-area', 'map-v2', 'pin-test-v2', 'booth', 0.75, 0.25, 'place-test', NULL)
            """, parameters(revisionId));

        CatalogSnapshot snapshot = store.loadPublished();

        assertThat(snapshot.pinsFor("map-area", "map-v1"))
            .singleElement()
            .satisfies(pin -> assertThat(pin.x()).isEqualByComparingTo("0.5"));
        assertThat(snapshot.pinsFor("map-area", "map-v2")).isEmpty();
    }

    @Test
    @Transactional
    void rejectsReusingAMapVersionWhenAreaPinChangesResolvedTargetMap() {
        UUID publishedRevisionId = publishedRevisionId();
        insertCatalogFixture(publishedRevisionId);
        MapSqlParameterSource published = parameters(publishedRevisionId);
        jdbc.update("""
            INSERT INTO map_areas (festival_revision_id, id, target_map_id)
            VALUES (:revisionId, 'area-route', 'map-area')
            """, published);
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-overview', 'overview-v1', 'pin-area-route', 'area', 0.25, 0.5, NULL, 'area-route')
            """, published);
        jdbc.update("""
            INSERT INTO map_pin_translations (
                festival_revision_id, map_id, map_version, pin_id, locale, label
            ) VALUES (:revisionId, 'map-overview', 'overview-v1', 'pin-area-route', 'ko', '구역 이동')
            """, published);

        MapSqlParameterSource archived = insertArchivedRevision(publishedRevisionId);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-overview', 'OVERVIEW', 1, 'overview-v1')
            """, archived);
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-overview', 'overview-v1', '/assets/maps/overview-v1.png', '전체 테스트 지도', 1000, 600)
            """, archived);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-area-other', 'AREA', 2, 'other-v1')
            """, archived);
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-area-other', 'other-v1', '/assets/maps/other-v1.png', '다른 구역 지도', 1000, 600)
            """, archived);
        jdbc.update("""
            INSERT INTO map_areas (festival_revision_id, id, target_map_id)
            VALUES (:revisionId, 'area-route', 'map-area-other')
            """, archived);
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-overview', 'overview-v1', 'pin-area-route', 'area', 0.25, 0.5, NULL, 'area-route')
            """, archived);

        assertThatThrownBy(store::loadPublished).isInstanceOf(CatalogIntegrityException.class);
    }

    private UUID publishedRevisionId() {
        return jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'", Map.of(), UUID.class
        );
    }

    private MapSqlParameterSource insertArchivedRevision(UUID publishedRevisionId) {
        UUID festivalId = jdbc.queryForObject(
            "SELECT festival_id FROM festival_revisions WHERE id = :revisionId",
            parameters(publishedRevisionId),
            UUID.class
        );
        MapSqlParameterSource archived = new MapSqlParameterSource()
            .addValue("revisionId", UUID.randomUUID())
            .addValue("festivalId", festivalId);
        jdbc.update("""
            INSERT INTO festival_revisions (
                id, festival_id, revision_number, state, approved_at, scheduled_at, published_at, created_at, updated_at
            ) VALUES (:revisionId, :festivalId, 2, 'archived', CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, archived);
        return archived;
    }

    private void insertCatalogFixture(UUID revisionId) {
        MapSqlParameterSource parameters = parameters(revisionId);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-overview', 'OVERVIEW', 1, 'overview-v1')
            """, parameters);
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-overview', 'overview-v1', '/assets/maps/overview-v1.png', '전체 테스트 지도', 1000, 600)
            """, parameters);
        jdbc.update("""
            INSERT INTO map_translations (festival_revision_id, map_id, locale, name)
            VALUES (:revisionId, 'map-overview', 'ko', '테스트 전체 지도')
            """, parameters);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-area', 'AREA', 2, 'map-v1')
            """, parameters);
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-area', 'map-v1', '/assets/maps/map-v1.png', '테스트 지도', 1000, 600)
            """, parameters);
        jdbc.update("""
            INSERT INTO map_translations (festival_revision_id, map_id, locale, name)
            VALUES (:revisionId, 'map-area', 'ko', '테스트 구역')
            """, parameters);
        jdbc.update("""
            INSERT INTO spaces (festival_revision_id, id, category, image_url, image_width, image_height)
            VALUES (:revisionId, 'space-test', 'BOOTH', '/assets/spaces/test.png', 100, 100)
            """, parameters);
        jdbc.update("""
            INSERT INTO space_translations (
                festival_revision_id, space_id, locale, name, image_alt, location_text, experience_text
            ) VALUES (:revisionId, 'space-test', 'ko', '테스트 부스', '테스트 부스', '테스트 위치', '테스트 체험')
            """, parameters);
        jdbc.update("""
            INSERT INTO space_sort_orders (festival_revision_id, locale, space_id, sort_rank)
            VALUES (:revisionId, 'ko', 'space-test', 1)
            """, parameters);
        jdbc.update("""
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            VALUES (:revisionId, 'place-test', 'SPACE', 'space-test')
            """, parameters);
        jdbc.update("""
            INSERT INTO place_translations (festival_revision_id, place_id, locale, name)
            VALUES (:revisionId, 'place-test', 'ko', '테스트 부스')
            """, parameters);
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-area', 'map-v1', 'pin-test', 'booth', 0.5, 0.25, 'place-test', NULL)
            """, parameters);
        jdbc.update("""
            INSERT INTO map_pin_translations (
                festival_revision_id, map_id, map_version, pin_id, locale, label
            ) VALUES (:revisionId, 'map-area', 'map-v1', 'pin-test', 'ko', '테스트 부스')
            """, parameters);
        jdbc.update("""
            INSERT INTO space_map_targets (
                festival_revision_id, space_id, map_id, map_version, pin_id, place_id
            ) VALUES (:revisionId, 'space-test', 'map-area', 'map-v1', 'pin-test', 'place-test')
            """, parameters);
        jdbc.update("""
            UPDATE ticket_guide
            SET map_id = 'map-area', place_id = 'place-test', pin_id = 'pin-test', map_version = 'map-v1'
            WHERE id = 1
            """, parameters);
    }

    private void insertOverviewPlacePin(UUID revisionId, String pinId) {
        MapSqlParameterSource parameters = parameters(revisionId)
            .addValue("pinId", pinId);
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-overview', 'overview-v1', :pinId, 'booth', 0.5, 0.25, 'place-test', NULL)
            """, parameters);
        jdbc.update("""
            INSERT INTO map_pin_translations (
                festival_revision_id, map_id, map_version, pin_id, locale, label
            ) VALUES (:revisionId, 'map-overview', 'overview-v1', :pinId, 'ko', '테스트 부스 전체 위치')
            """, parameters);
    }

    private void insertAreaPin(UUID revisionId, String pinId) {
        MapSqlParameterSource parameters = parameters(revisionId)
            .addValue("pinId", pinId);
        jdbc.update("""
            INSERT INTO map_areas (festival_revision_id, id, target_map_id)
            VALUES (:revisionId, 'area-route', 'map-overview')
            """, parameters);
        jdbc.update("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id
            ) VALUES (:revisionId, 'map-area', 'map-v1', :pinId, 'area', 0.25, 0.5, NULL, 'area-route')
            """, parameters);
        jdbc.update("""
            INSERT INTO map_pin_translations (
                festival_revision_id, map_id, map_version, pin_id, locale, label
            ) VALUES (:revisionId, 'map-area', 'map-v1', :pinId, 'ko', '구역 이동')
            """, parameters);
    }

    private void insertMapVersion(UUID revisionId, String version) {
        jdbc.update("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, 'map-area', :version, '/assets/maps/' || :version || '.png', '새 테스트 지도', 1000, 600)
            """, parameters(revisionId).addValue("version", version));
    }

    private MapSqlParameterSource parameters(UUID revisionId) {
        return new MapSqlParameterSource("revisionId", revisionId);
    }
}

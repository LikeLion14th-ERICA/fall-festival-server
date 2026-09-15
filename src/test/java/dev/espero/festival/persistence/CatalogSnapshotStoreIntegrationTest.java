package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.domain.CatalogSnapshot;
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
        assertThat(snapshot.ticketMapTarget()).isNull();
    }

    @Test
    @Transactional
    void loadsACanonicalSpaceAndTicketPlacePinTarget() {
        UUID revisionId = publishedRevisionId();
        insertCatalogFixture(revisionId);

        CatalogSnapshot snapshot = store.loadPublished();

        assertThat(snapshot.findSpace("space-test")).hasValueSatisfying(space -> {
            assertThat(space.mapTarget()).isEqualTo(new CatalogSnapshot.MapTarget(
                "map-area", "place-test", "pin-test", "map-v1"
            ));
        });
        assertThat(snapshot.pinsFor("map-area", "map-v1")).singleElement().satisfies(pin -> {
            assertThat(pin.target()).isEqualTo(new CatalogSnapshot.PinTarget("PLACE", "place-test"));
        });
        assertThat(snapshot.ticketMapTarget()).isEqualTo(new CatalogSnapshot.MapTarget(
            "map-area", "place-test", "pin-test", "map-v1"
        ));
    }

    @Test
    void rejectsAPartialTicketMapTarget() {
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE ticket_guide SET map_id = 'map-area' WHERE id = 1", Map.of()
        )).isInstanceOf(DataIntegrityViolationException.class);
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

    private UUID publishedRevisionId() {
        return jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'", Map.of(), UUID.class
        );
    }

    private void insertCatalogFixture(UUID revisionId) {
        MapSqlParameterSource parameters = parameters(revisionId);
        jdbc.update("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, 'map-area', 'AREA', 1, 'map-v1')
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
                festival_revision_id, space_id, locale, name, image_alt, location_text
            ) VALUES (:revisionId, 'space-test', 'ko', '테스트 부스', '테스트 부스', '테스트 위치')
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

    private MapSqlParameterSource parameters(UUID revisionId) {
        return new MapSqlParameterSource("revisionId", revisionId);
    }
}

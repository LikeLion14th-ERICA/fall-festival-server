package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.CatalogSnapshot.CatalogMap;
import dev.espero.festival.domain.CatalogSnapshot.Image;
import dev.espero.festival.domain.CatalogSnapshot.Pin;
import dev.espero.festival.domain.CatalogSnapshot.PinKey;
import dev.espero.festival.domain.CatalogSnapshot.PinTarget;
import dev.espero.festival.domain.CatalogSnapshot.Place;
import dev.espero.festival.domain.CatalogSnapshot.Space;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CatalogControllerTest {

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final CatalogController controller = new CatalogController(
        snapshots,
        ApiMetaTestFixtures.contentMetaSupport(
            Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC)
        )
    );

    @Test
    void servesThePublishedEmptyCatalog() {
        when(snapshots.required()).thenReturn(emptySnapshot());
        HttpServletRequest request = request(Map.of());

        ApiResponse<CatalogResponses.Spaces> spaces = controller.getSpaces(request);
        ApiResponse<CatalogResponses.Maps> maps = controller.getMaps(request);

        assertThat(spaces.data().items()).isEmpty();
        assertThat(maps.data().items()).isEmpty();
        assertThat(maps.data().overviewId()).isNull();
        assertThat(spaces.meta().festivalId()).isEqualTo("festival-catalog");
        assertThat(spaces.meta().revision()).isEqualTo(3);
    }

    @Test
    void filtersSpacesAndKeepsTheirCanonicalMapTarget() {
        when(snapshots.required()).thenReturn(snapshotWithMap());
        HttpServletRequest request = request(Map.of("category", new String[] {"BOOTH"}));
        when(request.getParameter("category")).thenReturn("BOOTH");

        ApiResponse<CatalogResponses.Spaces> response = controller.getSpaces(request);

        assertThat(response.data().items()).singleElement().satisfies(space -> {
            assertThat(space.id()).isEqualTo("space-test");
            assertThat(space.mapTarget()).isEqualTo(new CatalogResponses.MapTarget(
                "map-area", "place-test", "pin-test", "map-v1"
            ));
            assertThat(space.experience()).isEqualTo("체험 안내");
        });
    }

    @Test
    void rejectsUnknownAndDuplicateQueries() {
        when(snapshots.required()).thenReturn(emptySnapshot());
        HttpServletRequest unknown = request(Map.of("revision", new String[] {"3"}));
        HttpServletRequest duplicate = request(Map.of("category", new String[] {"BOOTH", "PUB"}));

        assertThatThrownBy(() -> controller.getSpaces(unknown))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.getSpaces(duplicate))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("INVALID_QUERY"));
    }

    @Test
    void distinguishesKnownLocalesThatAreNotReadyFromUnknownLocales() {
        when(snapshots.required()).thenReturn(emptySnapshot());
        HttpServletRequest knownButUnready = request(Map.of("locale", new String[] {"en"}));
        HttpServletRequest unknown = request(Map.of("locale", new String[] {"xx"}));
        when(knownButUnready.getParameter("locale")).thenReturn("en");
        when(unknown.getParameter("locale")).thenReturn("xx");

        assertThatThrownBy(() -> controller.getMaps(knownButUnready))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("LOCALE_NOT_READY"));
        assertThatThrownBy(() -> controller.getMaps(unknown))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("INVALID_QUERY"));
    }

    @Test
    void returnsVersionConflictOnlyForAnExistingMapWithAnOldVersion() {
        when(snapshots.required()).thenReturn(snapshotWithMap());
        HttpServletRequest oldVersion = request(Map.of("mapVersion", new String[] {"old-version"}));
        when(oldVersion.getParameter("mapVersion")).thenReturn("old-version");

        assertThatThrownBy(() -> controller.getPins("map-area", oldVersion))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                assertThat(apiException.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(apiException.code()).isEqualTo("MAP_VERSION_MISMATCH");
            });
    }

    @Test
    void returnsNotFoundForUnknownMapBeforeApplyingVersionComparison() {
        when(snapshots.required()).thenReturn(snapshotWithMap());
        HttpServletRequest request = request(Map.of("mapVersion", new String[] {"old-version"}));
        when(request.getParameter("mapVersion")).thenReturn("old-version");

        assertThatThrownBy(() -> controller.getPins("no-such-map", request))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                assertThat(apiException.status()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(apiException.code()).isEqualTo("NOT_FOUND");
            });
    }

    private CatalogSnapshot emptySnapshot() {
        return new CatalogSnapshot(context(), List.of(), List.of(), List.of(), Map.of(), null);
    }

    private CatalogSnapshot snapshotWithMap() {
        CatalogSnapshot.MapTarget target = new CatalogSnapshot.MapTarget(
            "map-area", "place-test", "pin-test", "map-v1"
        );
        Space space = new Space(
            "space-test", "BOOTH", "테스트 부스", new Image("/assets/test.png", "테스트 부스", 100, 100),
            "테스트 위치", null, null, null, "체험 안내", null, List.of(), List.of(), target
        );
        CatalogMap map = new CatalogMap(
            "map-area", "테스트 구역", "AREA", "map-v1", new Image("/assets/map.png", "테스트 지도", 1000, 600)
        );
        Place place = new Place("place-test", "SPACE", "테스트 부스", null, null, null, null, "space-test");
        Pin pin = new Pin(
            "pin-test", "booth", "PHOTO_BOOTH", "포토부스", "테스트 부스", new BigDecimal("0.5"), new BigDecimal("0.25"),
            new PinTarget("PLACE", "place-test")
        );
        return new CatalogSnapshot(
            context(),
            List.of(space),
            List.of(map),
            List.of(place),
            Map.of(new PinKey("map-area", "map-v1"), List.of(pin)),
            null
        );
    }

    private CatalogSnapshot.FestivalContext context() {
        return new CatalogSnapshot.FestivalContext(
            "festival-catalog", UUID.fromString("00000000-0000-0000-0000-000000000003"), 3
        );
    }

    @Test
    void returnsOnlyPlacePinGroupsInTheStableContractOrder() {
        when(snapshots.required()).thenReturn(snapshotWithMap());
        HttpServletRequest request = request(Map.of("mapVersion", new String[] {"map-v1"}));
        when(request.getParameter("mapVersion")).thenReturn("map-v1");

        ApiResponse<CatalogResponses.Pins> response = controller.getPins("map-area", request);

        assertThat(response.data().filters())
            .extracting(CatalogResponses.PinFilter::id, CatalogResponses.PinFilter::label)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("PHOTO_BOOTH", "포토부스"));
    }

    private HttpServletRequest request(Map<String, String[]> parameters) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterMap()).thenReturn(parameters);
        return request;
    }
}

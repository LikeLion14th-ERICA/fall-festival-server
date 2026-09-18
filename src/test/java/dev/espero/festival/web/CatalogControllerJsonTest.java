package dev.espero.festival.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.CatalogSnapshot.CatalogMap;
import dev.espero.festival.domain.CatalogSnapshot.Image;
import dev.espero.festival.domain.CatalogSnapshot.Pin;
import dev.espero.festival.domain.CatalogSnapshot.PinKey;
import dev.espero.festival.domain.CatalogSnapshot.PinTarget;
import dev.espero.festival.domain.CatalogSnapshot.Space;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hamcrest.Matchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogControllerJsonTest {

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(
            Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC)
        );
        mvc = MockMvcBuilders.standaloneSetup(new CatalogController(snapshots, metaSupport)).build();
    }

    @Test
    void serializesSpacesEnvelopeExperienceAndNullableCanonicalMapTarget() throws Exception {
        Space withTarget = new Space(
            "space-with-target", "BOOTH", "테스트 부스", new Image("/assets/booth.png", "부스", 100, 80),
            "학생회관 앞", null, null, null, "체험 안내", null, List.of("행사 안내"), List.of(),
            new CatalogSnapshot.MapTarget("map-area", "place-booth", "pin-booth", "map-v1")
        );
        Space withoutTarget = new Space(
            "space-without-target", "PUB", "테스트 주점", new Image("/assets/pub.png", "주점", 100, 80), "축제 거리", null, null, null,
            null, null, List.of(), List.of(), null
        );
        when(snapshots.required()).thenReturn(snapshot(List.of(withTarget, withoutTarget), List.of(), Map.of()));

        mvc.perform(get("/api/v2/spaces")
                .header("X-Request-Id", "catalog-json-test")
                .param("category", "ALL"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.data.items[0].experience").value("체험 안내"))
            .andExpect(jsonPath("$.data.items[0].mapTarget.mapId").value("map-area"))
            .andExpect(jsonPath("$.data.items[0].mapTarget.placeId").value("place-booth"))
            .andExpect(jsonPath("$.data.items[0].mapTarget.pinId").value("pin-booth"))
            .andExpect(jsonPath("$.data.items[0].mapTarget.mapVersion").value("map-v1"))
            .andExpect(jsonPath("$.data.items[1].experience").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[1].mapTarget").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.meta.requestId").value(Matchers.matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
            .andExpect(jsonPath("$.meta.serverTime").value("2030-10-01T18:00:00+09:00"))
            .andExpect(jsonPath("$.meta.timezone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.meta.festivalId").value("festival-catalog"))
            .andExpect(jsonPath("$.meta.revision").value(3))
            .andExpect(jsonPath("$.meta.locale").value("ko"))
            .andExpect(jsonPath("$.meta.mock").value(false));
    }

    @Test
    void serializesMicrosecondClockAsAnApiV2MillisecondTimestamp() throws Exception {
        ApiMetaSupport microsecondClock = ApiMetaTestFixtures.contentMetaSupport(
            Clock.fixed(Instant.parse("2030-10-01T09:00:00.123456789Z"), ZoneOffset.UTC)
        );
        mvc = MockMvcBuilders.standaloneSetup(new CatalogController(snapshots, microsecondClock)).build();
        when(snapshots.required()).thenReturn(snapshot(List.of(), List.of(), Map.of()));

        mvc.perform(get("/api/v2/maps").header("X-Request-Id", "microsecond-clock-test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.serverTime").value("2030-10-01T18:00:00.123+09:00"));
    }

    @Test
    void serializesMapPinsEnvelopeCoordinatesAndPlaceOrAreaTargets() throws Exception {
        Pin placePin = new Pin(
            "pin-place", "booth", "PHOTO_BOOTH", "포토부스", "부스", new BigDecimal("0.1250"), new BigDecimal("0.8750"),
            new PinTarget("PLACE", "place-booth")
        );
        Pin areaPin = new Pin(
            "pin-area", "zone", null, null, "구역", new BigDecimal("0.5"), new BigDecimal("0.25"),
            new PinTarget("AREA", "map-detail")
        );
        CatalogMap map = new CatalogMap(
            "map-overview", "전체 지도", "OVERVIEW", "map-v1", new Image("/assets/map.png", "지도", 1000, 600)
        );
        when(snapshots.required()).thenReturn(snapshot(
            List.of(), List.of(map), Map.of(new PinKey("map-overview", "map-v1"), List.of(placePin, areaPin))
        ));

        mvc.perform(get("/api/v2/maps/map-overview/pins")
                .header("X-Request-Id", "pins-json-test")
                .param("mapVersion", "map-v1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mapId").value("map-overview"))
            .andExpect(jsonPath("$.data.mapVersion").value("map-v1"))
            .andExpect(jsonPath("$.data.filters[0].id").value("PHOTO_BOOTH"))
            .andExpect(jsonPath("$.data.filters[0].label").value("포토부스"))
            .andExpect(jsonPath("$.data.items[0].x").value(0.125))
            .andExpect(jsonPath("$.data.items[0].y").value(0.875))
            .andExpect(jsonPath("$.data.items[0].target.kind").value("PLACE"))
            .andExpect(jsonPath("$.data.items[0].target.placeId").value("place-booth"))
            .andExpect(jsonPath("$.data.items[0].filterGroup").value("PHOTO_BOOTH"))
            .andExpect(jsonPath("$.data.items[0].target.mapId").doesNotExist())
            .andExpect(jsonPath("$.data.items[1].x").value(0.5))
            .andExpect(jsonPath("$.data.items[1].y").value(0.25))
            .andExpect(jsonPath("$.data.items[1].target.kind").value("AREA"))
            .andExpect(jsonPath("$.data.items[1].target.mapId").value("map-detail"))
            .andExpect(jsonPath("$.data.items[1].filterGroup").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[1].target.placeId").doesNotExist())
            .andExpect(jsonPath("$.meta.requestId").value(Matchers.matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
    }

    private CatalogSnapshot snapshot(
        List<Space> spaces,
        List<CatalogMap> maps,
        Map<PinKey, List<Pin>> pins
    ) {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                "festival-catalog", UUID.fromString("00000000-0000-0000-0000-000000000003"), 3
            ),
            spaces,
            maps,
            List.of(),
            pins,
            null
        );
    }
}

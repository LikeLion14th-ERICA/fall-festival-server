package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.CatalogSnapshot.CatalogMap;
import dev.espero.festival.domain.CatalogSnapshot.Pin;
import dev.espero.festival.domain.CatalogSnapshot.Space;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, immutable-snapshot implementation of the API v2 catalog routes. */
@RestController
@Profile("db")
@RequestMapping("/api/v2")
public class CatalogController {

    private static final String LOCALE = "ko";

    private final CatalogSnapshotProvider snapshots;
    private final ApiMetaSupport metaSupport;

    public CatalogController(CatalogSnapshotProvider snapshots, ApiMetaSupport metaSupport) {
        this.snapshots = snapshots;
        this.metaSupport = metaSupport;
    }

    @GetMapping("/spaces")
    public ApiResponse<CatalogResponses.Spaces> getSpaces(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshot(request);
        validateQuery(request, Set.of("category", "locale"));
        String category = valueOrDefault(request, "category", "ALL");
        if (!Set.of("ALL", "BOOTH", "PUB", "FLEA_MARKET").contains(category)) {
            throw invalidQuery();
        }
        List<CatalogResponses.Space> items = snapshot.spaces().stream()
            .filter(space -> category.equals("ALL") || space.category().equals(category))
            .map(CatalogController::spaceResponse)
            .toList();
        return new ApiResponse<>(new CatalogResponses.Spaces(items), meta(snapshot, request));
    }

    @GetMapping("/spaces/{spaceId}")
    public ApiResponse<CatalogResponses.Space> getSpace(
        @PathVariable String spaceId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request);
        validateQuery(request, Set.of("locale"));
        Space space = snapshot.findSpace(spaceId).orElseThrow(this::notFound);
        return new ApiResponse<>(spaceResponse(space), meta(snapshot, request));
    }

    @GetMapping("/maps")
    public ApiResponse<CatalogResponses.Maps> getMaps(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshot(request);
        validateQuery(request, Set.of("locale"));
        List<CatalogResponses.MapInfo> items = snapshot.maps().stream().map(CatalogController::mapResponse).toList();
        return new ApiResponse<>(
            new CatalogResponses.Maps(items, snapshot.overviewId().orElse(null)),
            meta(snapshot, request)
        );
    }

    @GetMapping("/maps/{mapId}")
    public ApiResponse<CatalogResponses.MapInfo> getMap(
        @PathVariable String mapId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request);
        validateQuery(request, Set.of("locale"));
        CatalogMap map = snapshot.findMap(mapId).orElseThrow(this::notFound);
        return new ApiResponse<>(mapResponse(map), meta(snapshot, request));
    }

    @GetMapping("/maps/{mapId}/pins")
    public ApiResponse<CatalogResponses.Pins> getPins(
        @PathVariable String mapId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request);
        validateQuery(request, Set.of("mapVersion", "locale"));
        String requestedVersion = requiredValue(request, "mapVersion");
        CatalogMap map = snapshot.findMap(mapId).orElseThrow(this::notFound);
        if (!map.version().equals(requestedVersion)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "MAP_VERSION_MISMATCH",
                "지도 버전이 최신 상태가 아닙니다.",
                true
            );
        }
        List<CatalogResponses.Pin> items = snapshot.pinsFor(mapId, requestedVersion).stream()
            .map(CatalogController::pinResponse)
            .toList();
        return new ApiResponse<>(new CatalogResponses.Pins(mapId, requestedVersion, items), meta(snapshot, request));
    }

    @GetMapping("/places/{placeId}")
    public ApiResponse<CatalogResponses.Place> getPlace(
        @PathVariable String placeId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request);
        validateQuery(request, Set.of("locale"));
        CatalogSnapshot.Place place = snapshot.findPlace(placeId).orElseThrow(this::notFound);
        return new ApiResponse<>(new CatalogResponses.Place(
            place.id(),
            place.kind(),
            place.name(),
            place.locationText(),
            place.hoursText(),
            place.description(),
            place.usage(),
            place.spaceId()
        ), meta(snapshot, request));
    }

    private CatalogSnapshot snapshot(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setContext(request, snapshot.context(), LOCALE);
        return snapshot;
    }

    private ApiMeta meta(CatalogSnapshot snapshot, HttpServletRequest request) {
        return metaSupport.meta(request, snapshot.context(), LOCALE);
    }

    private void validateQuery(HttpServletRequest request, Set<String> allowed) {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw invalidQuery();
            }
        }
        String locale = request.getParameter("locale");
        if (locale != null && !locale.equals(LOCALE)) {
            throw invalidQuery();
        }
    }

    private String requiredValue(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw invalidQuery();
        }
        return value;
    }

    private String valueOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getParameter(name);
        if (value == null) {
            return defaultValue;
        }
        if (value.isBlank()) {
            throw invalidQuery();
        }
        return value;
    }

    private ApiException invalidQuery() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", false);
    }

    private static CatalogResponses.Space spaceResponse(Space space) {
        CatalogResponses.Link contact = space.contact() == null
            ? null
            : new CatalogResponses.Link(space.contact().label(), space.contact().url(), "_blank");
        List<CatalogResponses.MenuItem> menu = space.menu().stream()
            .map(item -> new CatalogResponses.MenuItem(
                item.name(), new CatalogResponses.Money(item.amount(), "KRW")
            ))
            .toList();
        return new CatalogResponses.Space(
            space.id(),
            space.category(),
            space.name(),
            imageResponse(space.image()),
            space.locationText(),
            space.operator(),
            space.hoursText(),
            space.description(),
            contact,
            space.experience(),
            space.events(),
            menu,
            targetResponse(space.mapTarget())
        );
    }

    private static CatalogResponses.MapInfo mapResponse(CatalogMap map) {
        return new CatalogResponses.MapInfo(map.id(), map.name(), map.kind(), map.version(), imageResponse(map.image()));
    }

    private static CatalogResponses.Image imageResponse(CatalogSnapshot.Image image) {
        return new CatalogResponses.Image(image.url(), image.alt(), image.width(), image.height());
    }

    private static CatalogResponses.MapTarget targetResponse(CatalogSnapshot.MapTarget target) {
        return target == null ? null : new CatalogResponses.MapTarget(
            target.mapId(), target.placeId(), target.pinId(), target.mapVersion()
        );
    }

    private static CatalogResponses.Pin pinResponse(Pin pin) {
        CatalogResponses.PinTarget target = pin.target().kind().equals("PLACE")
            ? new CatalogResponses.PlacePinTarget("PLACE", pin.target().id())
            : new CatalogResponses.AreaPinTarget("AREA", pin.target().id());
        return new CatalogResponses.Pin(pin.id(), pin.category(), pin.label(), pin.x(), pin.y(), target);
    }
}

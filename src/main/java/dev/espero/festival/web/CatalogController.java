package dev.espero.festival.web;

import dev.espero.festival.account.OperationalAccountSetting;
import dev.espero.festival.account.OperationalAccountSettingsService;
import dev.espero.festival.account.OperationalAccountTarget;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.CatalogSnapshot.CatalogMap;
import dev.espero.festival.domain.CatalogSnapshot.Pin;
import dev.espero.festival.domain.CatalogSnapshot.Space;
import dev.espero.festival.domain.SpaceCategories;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, immutable-snapshot implementation of the API v2 catalog routes. */
@RestController
@Profile("db")
@RequestMapping("/api/v2")
public class CatalogController {


    private final CatalogSnapshotProvider snapshots;
    private final ApiMetaSupport metaSupport;
    private final Function<String, Optional<CatalogResponses.BankTransfer>> bankTransfers;

    /** Serves catalog routes without booth accounts. */
    public CatalogController(CatalogSnapshotProvider snapshots, ApiMetaSupport metaSupport) {
        this(snapshots, metaSupport, spaceId -> Optional.empty());
    }

    @Autowired
    public CatalogController(
        CatalogSnapshotProvider snapshots,
        ApiMetaSupport metaSupport,
        OperationalAccountSettingsService accounts,
        FestivalProperties festival
    ) {
        this(snapshots, metaSupport, spaceId -> accounts
            .findCurrent(OperationalAccountTarget.space(festival.configuredFestivalId(), spaceId))
            .filter(OperationalAccountSetting::isConfigured)
            .map(setting -> new CatalogResponses.BankTransfer(
                setting.bankCode(),
                setting.bankName(),
                setting.accountNumber(),
                setting.accountHolder(),
                setting.tossLinkEnabled()
            )));
    }

    CatalogController(
        CatalogSnapshotProvider snapshots,
        ApiMetaSupport metaSupport,
        Function<String, Optional<CatalogResponses.BankTransfer>> bankTransfers
    ) {
        this.snapshots = snapshots;
        this.metaSupport = metaSupport;
        this.bankTransfers = bankTransfers;
    }

    @GetMapping("/spaces")
    public ApiResponse<CatalogResponses.Spaces> getSpaces(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshot(request, Set.of("category", "locale"));
        String category = valueOrDefault(request, "category", "ALL");
        if (!category.equals("ALL") && !SpaceCategories.ALL.contains(category)) {
            throw invalidQuery();
        }
        List<CatalogResponses.Space> items = snapshot.spaces().stream()
            .filter(space -> category.equals("ALL") || space.category().equals(category))
            .map(space -> spaceResponse(space, null))
            .toList();
        return new ApiResponse<>(new CatalogResponses.Spaces(items), meta(snapshot, request));
    }

    /**
     * The detail carries the booth's current receiving account, read on every
     * request so a changed account is never served from a cache.
     */
    @GetMapping("/spaces/{spaceId}")
    public ResponseEntity<ApiResponse<CatalogResponses.Space>> getSpace(
        @PathVariable String spaceId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request, Set.of("locale"));
        Space space = snapshot.findSpace(spaceId).orElseThrow(this::notFound);
        CatalogResponses.BankTransfer bankTransfer = bankTransfers.apply(space.id()).orElse(null);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(spaceResponse(space, bankTransfer), meta(snapshot, request)));
    }

    @GetMapping("/maps")
    public ApiResponse<CatalogResponses.Maps> getMaps(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshot(request, Set.of("locale"));
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
        CatalogSnapshot snapshot = snapshot(request, Set.of("locale"));
        CatalogMap map = snapshot.findMap(mapId).orElseThrow(this::notFound);
        return new ApiResponse<>(mapResponse(map), meta(snapshot, request));
    }

    @GetMapping("/maps/{mapId}/pins")
    public ApiResponse<CatalogResponses.Pins> getPins(
        @PathVariable String mapId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request, Set.of("mapVersion", "locale"));
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
        List<CatalogResponses.PinFilter> filters = snapshot.filtersFor(mapId, requestedVersion).stream()
            .map(filter -> new CatalogResponses.PinFilter(filter.id(), filter.label()))
            .toList();
        return new ApiResponse<>(new CatalogResponses.Pins(mapId, requestedVersion, filters, items), meta(snapshot, request));
    }

    @GetMapping("/places/{placeId}")
    public ApiResponse<CatalogResponses.Place> getPlace(
        @PathVariable String placeId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request, Set.of("locale"));
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

    /**
     * Validates the query and returns the snapshot in the requested locale.
     * Errors before that point carry the Korean context in their meta.
     */
    private CatalogSnapshot snapshot(HttpServletRequest request, Set<String> allowed) {
        CatalogSnapshot korean = snapshots.required();
        metaSupport.setContext(request, korean.context(), PublicContentLocale.KOREAN);
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw invalidQuery();
            }
        }
        String locale = PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
        CatalogSnapshot snapshot = PublicContentLocale.snapshot(snapshots, locale);
        metaSupport.setContext(request, snapshot.context(), locale);
        return snapshot;
    }

    private ApiMeta meta(CatalogSnapshot snapshot, HttpServletRequest request) {
        return metaSupport.meta(request, snapshot.context(), locale(request));
    }

    /** The locale snapshot(request, allowed) already accepted. */
    private static String locale(HttpServletRequest request) {
        String locale = request.getParameter("locale");
        return locale == null ? PublicContentLocale.KOREAN : locale;
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
        return PublicContentLocale.invalidQuery();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", false);
    }

    private static CatalogResponses.Space spaceResponse(Space space, CatalogResponses.BankTransfer bankTransfer) {
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
            targetResponse(space.mapTarget()),
            bankTransfer
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
        return new CatalogResponses.Pin(
            pin.id(), pin.category(), pin.filterGroup(), pin.label(), pin.x(), pin.y(), target
        );
    }
}

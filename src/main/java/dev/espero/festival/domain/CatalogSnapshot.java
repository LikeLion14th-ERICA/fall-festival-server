package dev.espero.festival.domain;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fully validated, immutable public catalog for one published festival
 * revision. It deliberately contains no draft rows or write capabilities.
 */
public record CatalogSnapshot(
    FestivalContext context,
    List<Space> spaces,
    List<CatalogMap> maps,
    List<Place> places,
    Map<PinKey, List<Pin>> pinsByMapVersion,
    TicketGuideConfig ticketGuideConfig,
    StampGuide stampGuide,
    MapTarget ticketMapTarget
) {

    /**
     * The public map filter order is part of the v2 response contract. It is
     * deliberately independent of database insertion order or map sort rank.
     */
    private static final List<String> FILTER_GROUP_ORDER = List.of(
        "RESTROOM",
        "PHOTO_BOOTH",
        "SMOKING_AREA",
        "TRASH_BIN"
    );

    public CatalogSnapshot(
        FestivalContext context,
        List<Space> spaces,
        List<CatalogMap> maps,
        List<Place> places,
        Map<PinKey, List<Pin>> pinsByMapVersion,
        MapTarget ticketMapTarget
    ) {
        this(context, spaces, maps, places, pinsByMapVersion, null, null, ticketMapTarget);
    }

    /** Compatibility constructor for callers that only supplied ticket data. */
    public CatalogSnapshot(
        FestivalContext context,
        List<Space> spaces,
        List<CatalogMap> maps,
        List<Place> places,
        Map<PinKey, List<Pin>> pinsByMapVersion,
        TicketGuideConfig ticketGuideConfig,
        MapTarget ticketMapTarget
    ) {
        this(context, spaces, maps, places, pinsByMapVersion, ticketGuideConfig, null, ticketMapTarget);
    }

    public CatalogSnapshot {
        spaces = List.copyOf(spaces);
        maps = List.copyOf(maps);
        places = List.copyOf(places);
        Map<PinKey, List<Pin>> copiedPins = new LinkedHashMap<>();
        pinsByMapVersion.forEach((key, pins) -> copiedPins.put(key, List.copyOf(pins)));
        pinsByMapVersion = Map.copyOf(copiedPins);
    }

    public Optional<Space> findSpace(String id) {
        return spaces.stream().filter(space -> space.id().equals(id)).findFirst();
    }

    public Optional<CatalogMap> findMap(String id) {
        return maps.stream().filter(map -> map.id().equals(id)).findFirst();
    }

    public Optional<Place> findPlace(String id) {
        return places.stream().filter(place -> place.id().equals(id)).findFirst();
    }

    public List<Pin> pinsFor(String mapId, String mapVersion) {
        return pinsByMapVersion.getOrDefault(new PinKey(mapId, mapVersion), List.of());
    }

    /**
     * Returns only filter groups represented by PLACE pins for this exact
     * map/version. AREA pins are navigation affordances and are intentionally
     * excluded from the filter list so the client can keep them visible.
     */
    public List<PinFilter> filtersFor(String mapId, String mapVersion) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Pin pin : pinsFor(mapId, mapVersion)) {
            if (!"PLACE".equals(pin.target().kind()) || pin.filterGroup() == null) {
                continue;
            }
            labels.putIfAbsent(pin.filterGroup(), pin.filterGroupLabel());
        }
        return FILTER_GROUP_ORDER.stream()
            .filter(labels::containsKey)
            .map(group -> new PinFilter(group, labels.get(group)))
            .toList();
    }

    public Optional<String> overviewId() {
        return maps.stream()
            .filter(map -> map.kind().equals("OVERVIEW"))
            .map(CatalogMap::id)
            .findFirst();
    }

    public record FestivalContext(String festivalId, UUID revisionId, long revision, ZoneId timezone) {

        public FestivalContext(String festivalId, UUID revisionId, long revision) {
            this(festivalId, revisionId, revision, ZoneId.of("Asia/Seoul"));
        }
    }

    public record Image(String url, String alt, int width, int height) {}

    public record Link(String label, String url) {}

    public record Money(String name, int amount) {}

    public record MapTarget(String mapId, String placeId, String pinId, String mapVersion) {}

    public record Space(
        String id,
        String category,
        String name,
        Image image,
        String locationText,
        String operator,
        String hoursText,
        String description,
        String experience,
        Link contact,
        List<String> events,
        List<Money> menu,
        MapTarget mapTarget
    ) {
        public Space {
            events = List.copyOf(events);
            menu = List.copyOf(menu);
        }
    }

    public record CatalogMap(String id, String name, String kind, String version, Image image) {}

    public record Place(
        String id,
        String kind,
        String name,
        String locationText,
        String hoursText,
        String description,
        String usage,
        String spaceId
    ) {}

    public record Pin(
        String id,
        String category,
        String filterGroup,
        String filterGroupLabel,
        String label,
        BigDecimal x,
        BigDecimal y,
        PinTarget target
    ) {}

    public record PinFilter(String id, String label) {}

    public record PinTarget(String kind, String id) {}

    public record PinKey(String mapId, String mapVersion) {}
}

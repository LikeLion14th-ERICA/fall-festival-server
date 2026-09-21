package dev.espero.festival.persistence;

import dev.espero.festival.CatalogExportService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.CatalogSnapshot.CatalogMap;
import dev.espero.festival.domain.CatalogSnapshot.FestivalContext;
import dev.espero.festival.domain.CatalogSnapshot.Image;
import dev.espero.festival.domain.CatalogSnapshot.Link;
import dev.espero.festival.domain.CatalogSnapshot.MapTarget;
import dev.espero.festival.domain.CatalogSnapshot.Money;
import dev.espero.festival.domain.CatalogSnapshot.Pin;
import dev.espero.festival.domain.CatalogSnapshot.PinKey;
import dev.espero.festival.domain.CatalogSnapshot.PinTarget;
import dev.espero.festival.domain.CatalogSnapshot.Place;
import dev.espero.festival.domain.CatalogSnapshot.Space;
import dev.espero.festival.domain.SpaceCategories;
import dev.espero.festival.domain.StampGuide;
import dev.espero.festival.domain.TicketGuideConfig;
import dev.espero.festival.domain.PublishedFestivalContext;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads all public catalog data in one repeatable-read transaction. A bad
 * catalog is rejected here instead of becoming a partially visible response.
 */
@Repository
@Profile("db")
public class CatalogSnapshotStore {

    /** The base locale. Korean text lives in the base rows of single-language tables. */
    public static final String KOREAN = "ko";
    private static final Set<String> FILTER_GROUPS = Set.of(
        "RESTROOM",
        "PHOTO_BOOTH",
        "SMOKING_AREA",
        "TRASH_BIN"
    );
    private static final Pattern API_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final TicketGuideStore ticketGuideStore;
    private final StampGuideStore stampGuideStore;
    private final FestivalContextStore festivalContextStore;
    private final FestivalProperties festivalProperties;

    public CatalogSnapshotStore(
        NamedParameterJdbcTemplate jdbc,
        TicketGuideStore ticketGuideStore,
        StampGuideStore stampGuideStore,
        FestivalContextStore festivalContextStore,
        FestivalProperties festivalProperties
    ) {
        this.jdbc = jdbc;
        this.ticketGuideStore = ticketGuideStore;
        this.stampGuideStore = stampGuideStore;
        this.festivalContextStore = festivalContextStore;
        this.festivalProperties = festivalProperties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CatalogSnapshot loadPublished() {
        return loadPublished(KOREAN);
    }

    /**
     * Loads the published revision in one locale. Every translated field must
     * be present in that locale; a missing one rejects the whole snapshot
     * instead of falling back to Korean.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CatalogSnapshot loadPublished(String locale) {
        return loadRevision(loadPublishedContext().revisionId(), locale);
    }

    /**
     * Loads and validates any revision, including a draft. The caller can use
     * this method before publication so a malformed manifest never becomes
     * visible. The revision state itself is deliberately not restricted here;
     * loadPublished() is the only public-read entry point that requires the
     * published pointer.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CatalogSnapshot loadRevision(UUID revisionId) {
        return loadRevision(revisionId, KOREAN);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CatalogSnapshot loadRevision(UUID revisionId, String locale) {
        FestivalContext context = loadRevisionContext(revisionId);
        return load(context, locale);
    }

    private CatalogSnapshot load(FestivalContext context, String locale) {
        List<CatalogMap> maps = loadMaps(context, locale);
        List<Place> places = loadPlaces(context, locale);
        Map<String, List<String>> events = loadEvents(context, locale);
        Map<String, List<Money>> menus = loadMenus(context, locale);
        List<Space> spaces = loadSpaces(context, locale, events, menus);
        Map<PinKey, List<Pin>> pins = loadPins(context, locale, maps);
        TicketGuideConfig ticketGuideConfig = ticketGuideStore.find(context.revisionId(), locale).orElseThrow(
            () -> new CatalogIntegrityException("Catalog revision is missing its ticket guide in " + locale + ".")
        );
        StampGuide stampGuide = stampGuideStore.find(context.revisionId(), locale).orElseThrow(
            () -> new CatalogIntegrityException("Catalog revision is missing its stamp guide in " + locale + ".")
        );
        MapTarget ticketMapTarget = loadTicketMapTarget(context);
        CatalogSnapshot.FestivalHome home = loadHome(context, locale);

        verifySingleOverview(maps);
        verifySpaceContent(spaces);
        verifyPinTargets(maps, places, pins);
        verifyPinFilterGroups(pins);
        verifySpaceTargets(spaces, maps, places, pins);
        verifyTicketTarget(ticketMapTarget, maps, places, pins);
        verifyPublicContract(context, spaces, maps, places, pins, ticketMapTarget, ticketGuideConfig, stampGuide);
        verifyVersionHistory(context);

        return new CatalogSnapshot(
            context, spaces, maps, places, pins, ticketGuideConfig, stampGuide, ticketMapTarget, home
        );
    }

    /**
     * Festival title, days and home links. Every link must carry a label in
     * the requested locale; a link without one would otherwise disappear from
     * the home screen without notice.
     */
    private CatalogSnapshot.FestivalHome loadHome(FestivalContext context, String locale) {
        String title = jdbc.queryForObject("""
            SELECT CASE WHEN :locale = 'ko' THEN f.title ELSE t.title END
            FROM festival_revisions r
            JOIN festivals f ON f.id = r.festival_id
            LEFT JOIN festival_title_translations t
              ON t.festival_revision_id = r.id AND t.locale = :locale
            WHERE r.id = :revisionId
            """, parameters(context, locale), String.class);
        requireText(title, "Festival.title");
        List<java.time.LocalDate> dates = jdbc.query("""
            SELECT festival_date FROM festival_days
            WHERE festival_revision_id = :revisionId
            ORDER BY festival_date
            """, parameters(context, locale), (resultSet, rowNumber) -> resultSet.getObject("festival_date", java.time.LocalDate.class));
        List<CatalogSnapshot.HomeLink> links = jdbc.query("""
            SELECT l.id, l.kind, t.label, l.url, l.icon_key, l.sort_order
            FROM festival_links l
            LEFT JOIN festival_link_translations t
              ON t.festival_revision_id = l.festival_revision_id AND t.link_id = l.id AND t.locale = :locale
            WHERE l.festival_revision_id = :revisionId
            ORDER BY l.kind, l.sort_order, l.id
            """, parameters(context, locale), (resultSet, rowNumber) -> new CatalogSnapshot.HomeLink(
                resultSet.getString("id"),
                resultSet.getString("kind"),
                resultSet.getString("label"),
                resultSet.getString("url"),
                resultSet.getString("icon_key"),
                resultSet.getInt("sort_order")
            ));
        for (CatalogSnapshot.HomeLink link : links) {
            requireText(link.label(), "FestivalLinkTranslation.label");
            requireHttpsUri(link.url(), "FestivalLink.url");
        }
        return new CatalogSnapshot.FestivalHome(title, dates, links);
    }

    private FestivalContext loadPublishedContext() {
        PublishedFestivalContext context = festivalContextStore
            .findPublishedByFestivalId(festivalProperties.configuredFestivalId())
            .orElseThrow(() -> new CatalogIntegrityException(
                "The configured festival has no published revision."
            ));
        return new FestivalContext(
            context.festivalId().toString(),
            context.festivalRevisionId(),
            context.revisionNumber(),
            context.timezone()
        );
    }

    private FestivalContext loadRevisionContext(UUID revisionId) {
        if (revisionId == null) {
            throw new CatalogIntegrityException("A revision id is required.");
        }
        List<FestivalContext> contexts = jdbc.query("""
            SELECT f.id AS festival_id, f.timezone, r.id AS revision_id, r.revision_number
            FROM festival_revisions r
            JOIN festivals f ON f.id = r.festival_id
            WHERE r.id = :revisionId
            """, new MapSqlParameterSource("revisionId", revisionId), (resultSet, rowNumber) ->
            new FestivalContext(
                resultSet.getObject("festival_id", UUID.class).toString(),
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getLong("revision_number"),
                java.time.ZoneId.of(resultSet.getString("timezone"))
            )
        );
        if (contexts.size() != 1) {
            throw new CatalogIntegrityException("The requested festival revision does not exist.");
        }
        return contexts.getFirst();
    }

    private List<CatalogMap> loadMaps(FestivalContext context, String locale) {
        List<CatalogMap> maps = new ArrayList<>();
        jdbc.query("""
            SELECT m.id, m.kind, m.current_version, mt.name,
                   a.image_url, a.image_width, a.image_height,
                   CASE WHEN :locale = 'ko' THEN a.image_alt ELSE at.image_alt END AS image_alt
            FROM maps m
            JOIN map_asset_versions a
              ON a.festival_revision_id = m.festival_revision_id
             AND a.map_id = m.id
             AND a.version = m.current_version
            LEFT JOIN map_asset_translations at
              ON at.festival_revision_id = a.festival_revision_id
             AND at.map_id = a.map_id
             AND at.version = a.version
             AND at.locale = :locale
            LEFT JOIN map_translations mt
              ON mt.festival_revision_id = m.festival_revision_id
             AND mt.map_id = m.id
             AND mt.locale = :locale
            WHERE m.festival_revision_id = :revisionId
            ORDER BY m.sort_rank, m.id
            """, parameters(context, locale), resultSet -> {
            String name = resultSet.getString("name");
            require(name != null, "Published map is missing a translation.");
            require(resultSet.getString("image_alt") != null, "Published map image is missing its alt text.");
            maps.add(new CatalogMap(
                resultSet.getString("id"),
                name,
                resultSet.getString("kind"),
                resultSet.getString("current_version"),
                new Image(
                    resultSet.getString("image_url"),
                    resultSet.getString("image_alt"),
                    resultSet.getInt("image_width"),
                    resultSet.getInt("image_height")
                )
            ));
        });
        return List.copyOf(maps);
    }

    private List<Place> loadPlaces(FestivalContext context, String locale) {
        List<Place> places = new ArrayList<>();
        jdbc.query("""
            SELECT p.id, p.kind, p.space_id, pt.locale,
                   pt.name, pt.location_text, pt.hours_text, pt.description_text, pt.usage_text
            FROM places p
            LEFT JOIN place_translations pt
              ON pt.festival_revision_id = p.festival_revision_id
             AND pt.place_id = p.id
             AND pt.locale = :locale
            WHERE p.festival_revision_id = :revisionId
            ORDER BY p.id
            """, parameters(context, locale), resultSet -> {
            require(resultSet.getString("locale") != null, "Published place is missing a translation row.");
            places.add(new Place(
                resultSet.getString("id"),
                resultSet.getString("kind"),
                resultSet.getString("name"),
                resultSet.getString("location_text"),
                resultSet.getString("hours_text"),
                resultSet.getString("description_text"),
                resultSet.getString("usage_text"),
                resultSet.getString("space_id")
            ));
        });
        return List.copyOf(places);
    }

    private Map<String, List<String>> loadEvents(FestivalContext context, String locale) {
        Map<String, List<String>> events = new LinkedHashMap<>();
        jdbc.query("""
            SELECT space_id, content
            FROM space_events
            WHERE festival_revision_id = :revisionId AND locale = :locale
            ORDER BY space_id, sort_order
            """, parameters(context, locale), resultSet -> {
                events.computeIfAbsent(resultSet.getString("space_id"), ignored -> new ArrayList<>())
                    .add(resultSet.getString("content"));
            }
        );
        return immutableLists(events);
    }

    private Map<String, List<Money>> loadMenus(FestivalContext context, String locale) {
        Map<String, List<Money>> menus = new LinkedHashMap<>();
        jdbc.query("""
            SELECT space_id, name, price_amount
            FROM space_menu_items
            WHERE festival_revision_id = :revisionId AND locale = :locale
            ORDER BY space_id, sort_order
            """, parameters(context, locale), resultSet -> {
                menus.computeIfAbsent(resultSet.getString("space_id"), ignored -> new ArrayList<>())
                    .add(new Money(resultSet.getString("name"), resultSet.getInt("price_amount")));
            }
        );
        return immutableLists(menus);
    }

    private List<Space> loadSpaces(
        FestivalContext context,
        String locale,
        Map<String, List<String>> events,
        Map<String, List<Money>> menus
    ) {
        List<Space> spaces = new ArrayList<>();
        jdbc.query("""
            SELECT s.id, s.category, s.image_url, s.image_width, s.image_height,
                   st.locale, st.name, st.image_alt, st.location_text, st.operator_text,
                   st.hours_text, st.description_text, st.experience_text, st.contact_label, st.contact_url,
                   so.sort_rank,
                   smt.map_id, smt.place_id, smt.pin_id, smt.map_version
            FROM spaces s
            LEFT JOIN space_translations st
              ON st.festival_revision_id = s.festival_revision_id
             AND st.space_id = s.id
             AND st.locale = :locale
            LEFT JOIN space_sort_orders so
              ON so.festival_revision_id = s.festival_revision_id
             AND so.space_id = s.id
             AND so.locale = :locale
            LEFT JOIN space_map_targets smt
              ON smt.festival_revision_id = s.festival_revision_id
             AND smt.space_id = s.id
            WHERE s.festival_revision_id = :revisionId
            ORDER BY so.sort_rank NULLS LAST, s.id
            """, parameters(context, locale), resultSet -> {
            require(resultSet.getString("locale") != null, "Published space is missing a translation.");
            require(resultSet.getObject("sort_rank") != null, "Published space is missing a sort rank.");
            String contactLabel = resultSet.getString("contact_label");
            String contactUrl = resultSet.getString("contact_url");
            Link contact = contactLabel == null ? null : new Link(contactLabel, contactUrl);
            String spaceId = resultSet.getString("id");
            spaces.add(new Space(
                spaceId,
                resultSet.getString("category"),
                resultSet.getString("name"),
                new Image(
                    resultSet.getString("image_url"),
                    resultSet.getString("image_alt"),
                    resultSet.getInt("image_width"),
                    resultSet.getInt("image_height")
                ),
                resultSet.getString("location_text"),
                resultSet.getString("operator_text"),
                resultSet.getString("hours_text"),
                resultSet.getString("description_text"),
                resultSet.getString("experience_text"),
                contact,
                events.getOrDefault(spaceId, List.of()),
                menus.getOrDefault(spaceId, List.of()),
                targetOrNull(resultSet)
            ));
        });
        return List.copyOf(spaces);
    }

    private Map<PinKey, List<Pin>> loadPins(FestivalContext context, String locale, List<CatalogMap> maps) {
        Map<String, CatalogMap> mapsById = maps.stream()
            .collect(java.util.stream.Collectors.toMap(CatalogMap::id, map -> map));
        Map<PinKey, List<Pin>> pins = new LinkedHashMap<>();
        jdbc.query("""
            SELECT p.map_id, p.map_version, p.id, p.category, p.filter_group, p.x, p.y,
                   p.place_id, p.area_id, pt.label, fgt.label AS filter_group_label, a.target_map_id
            FROM map_pins p
            JOIN maps m
              ON m.festival_revision_id = p.festival_revision_id
             AND m.id = p.map_id
             AND m.current_version = p.map_version
            LEFT JOIN map_pin_translations pt
              ON pt.festival_revision_id = p.festival_revision_id
             AND pt.map_id = p.map_id
             AND pt.map_version = p.map_version
             AND pt.pin_id = p.id
             AND pt.locale = :locale
            LEFT JOIN map_pin_filter_group_translations fgt
              ON fgt.festival_revision_id = p.festival_revision_id
             AND fgt.filter_group = p.filter_group
             AND fgt.locale = :locale
            LEFT JOIN map_areas a
              ON a.festival_revision_id = p.festival_revision_id
             AND a.id = p.area_id
            WHERE p.festival_revision_id = :revisionId
            ORDER BY p.map_id, p.map_version, p.id
            """, parameters(context, locale), resultSet -> {
            require(resultSet.getString("label") != null, "Published map pin is missing a label.");
            String placeId = resultSet.getString("place_id");
            String filterGroup = resultSet.getString("filter_group");
            String filterGroupLabel = resultSet.getString("filter_group_label");
            if (placeId == null) {
                require(filterGroup == null,
                    "Published AREA pin must not have a filter group.");
                require(filterGroupLabel == null,
                    "Published AREA pin must not have a filter group label.");
            } else if (filterGroup != null) {
                require(filterGroupLabel != null,
                    "Published PLACE pin filter group is missing a label.");
            }
            PinTarget target = placeId != null
                ? new PinTarget("PLACE", placeId)
                : new PinTarget("AREA", resultSet.getString("target_map_id"));
            require(target.id() != null, "Published area pin has no target map.");
            if (target.kind().equals("AREA")) {
                CatalogMap targetMap = mapsById.get(target.id());
                require(targetMap != null && targetMap.kind().equals("AREA"),
                    "Published area pin must point to an AREA map in the same snapshot.");
            }
            PinKey key = new PinKey(resultSet.getString("map_id"), resultSet.getString("map_version"));
            pins.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Pin(
                resultSet.getString("id"),
                resultSet.getString("category"),
                filterGroup,
                filterGroupLabel,
                resultSet.getString("label"),
                resultSet.getBigDecimal("x"),
                resultSet.getBigDecimal("y"),
                target
            ));
        });
        return immutablePinLists(pins);
    }

    private MapTarget loadTicketMapTarget(FestivalContext context) {
        List<MapTarget> targets = jdbc.query("""
            SELECT current.map_id, current.place_id, current.pin_id, current.map_version
            FROM ticket_guide_revisions current
            WHERE current.id = 1 AND current.festival_revision_id = :revisionId
            """, parameters(context), (resultSet, rowNumber) -> targetOrNull(resultSet));
        if (targets.isEmpty()) {
            return null;
        }
        if (targets.size() != 1) {
            throw new CatalogIntegrityException("More than one current ticket guide was found.");
        }
        return targets.getFirst();
    }

    private void verifySingleOverview(List<CatalogMap> maps) {
        long overviewCount = maps.stream().filter(map -> map.kind().equals("OVERVIEW")).count();
        require(maps.isEmpty() || overviewCount == 1,
            "A non-empty published catalog must have exactly one overview map.");
    }

    private void verifySpaceContent(List<Space> spaces) {
        for (Space space : spaces) {
            require(SpaceCategories.ALL.contains(space.category()),
                "Published space has an unsupported category.");
            require(SpaceCategories.WITH_EVENTS.contains(space.category()) || space.events().isEmpty(),
                "Only booth-type spaces can publish events.");
            require(SpaceCategories.WITH_MENU.contains(space.category()) || space.menu().isEmpty(),
                "Only PUB and FOOD_TRUCK spaces can publish menus.");
        }
    }

    private void verifyPinTargets(
        List<CatalogMap> maps,
        List<Place> places,
        Map<PinKey, List<Pin>> pins
    ) {
        Map<String, Place> placesById = places.stream()
            .collect(java.util.stream.Collectors.toMap(Place::id, place -> place));
        for (List<Pin> pinsForMap : pins.values()) {
            for (Pin pin : pinsForMap) {
                if (pin.target().kind().equals("PLACE")) {
                    require(placesById.containsKey(pin.target().id()),
                        "Published place pin points to a missing place.");
                }
            }
        }
    }

    /**
     * Only facility pins that match a design filter (restroom, photo booth,
     * smoking area, trash bin) carry a filter group. Other PLACE pins have
     * none and are shown only under the client's "all" filter. A group in use
     * must have one Korean label.
     */
    private void verifyPinFilterGroups(Map<PinKey, List<Pin>> pins) {
        List<Pin> allPins = pins.values().stream().flatMap(List::stream).toList();
        Map<String, String> labels = new HashMap<>();
        for (Pin pin : allPins) {
            if (pin.target().kind().equals("PLACE")) {
                if (pin.filterGroup() == null) {
                    require(pin.filterGroupLabel() == null,
                        "Published PLACE pin without a filter group must not have a filter label.");
                    continue;
                }
                require(FILTER_GROUPS.contains(pin.filterGroup()),
                    "Published PLACE pin must have a supported filter group.");
                require(pin.filterGroupLabel() != null && !pin.filterGroupLabel().isBlank(),
                    "Published PLACE pin filter group must have a Korean label.");
                String previous = labels.putIfAbsent(pin.filterGroup(), pin.filterGroupLabel());
                require(previous == null || previous.equals(pin.filterGroupLabel()),
                    "A filter group must use one Korean label in a published revision.");
            } else {
                require(pin.filterGroup() == null && pin.filterGroupLabel() == null,
                    "Published AREA pin must not have a filter group.");
            }
        }
    }

    private void verifySpaceTargets(
        List<Space> spaces,
        List<CatalogMap> maps,
        List<Place> places,
        Map<PinKey, List<Pin>> pins
    ) {
        Map<String, Place> placesById = places.stream()
            .collect(java.util.stream.Collectors.toMap(Place::id, place -> place));
        for (Space space : spaces) {
            MapTarget target = space.mapTarget();
            if (target == null) {
                continue;
            }
            Place place = placesById.get(target.placeId());
            require(place != null && space.id().equals(place.spaceId()),
                "Space map target must return to the same space through its place.");
            require(maps.stream().anyMatch(map -> map.id().equals(target.mapId()) && map.kind().equals("AREA")),
                "Space map target must point to an AREA map.");
            requireCurrentMapTarget(target, maps, pins);
        }
    }

    private void verifyPublicContract(
        FestivalContext context,
        List<Space> spaces,
        List<CatalogMap> maps,
        List<Place> places,
        Map<PinKey, List<Pin>> pins,
        MapTarget ticketMapTarget,
        TicketGuideConfig ticketGuideConfig,
        StampGuide stampGuide
    ) {
        requireApiId(context.festivalId(), "Meta.festivalId");
        for (Space space : spaces) {
            requireApiId(space.id(), "Space.id");
            verifyImage(space.image(), "Space.image");
            if (space.contact() != null) {
                requireHttpsUri(space.contact().url(), "Space.contact.url");
            }
            verifyMapTargetIds(space.mapTarget(), "Space.mapTarget");
        }
        for (CatalogMap map : maps) {
            requireApiId(map.id(), "Map.id");
            requireText(map.version(), "Map.version");
            verifyImage(map.image(), "Map.image");
        }
        for (Place place : places) {
            requireApiId(place.id(), "Place.id");
            if (place.spaceId() != null) {
                requireApiId(place.spaceId(), "Place.spaceId");
            }
        }
        for (Map.Entry<PinKey, List<Pin>> entry : pins.entrySet()) {
            requireApiId(entry.getKey().mapId(), "Pins.mapId");
            requireText(entry.getKey().mapVersion(), "Pins.mapVersion");
            for (Pin pin : entry.getValue()) {
                requireApiId(pin.id(), "Pin.id");
                requireApiId(pin.target().id(), "Pin.target.id");
            }
        }
        verifyMapTargetIds(ticketMapTarget, "TicketGuide.mapTarget");
        verifyTicketGuide(ticketGuideConfig);
        verifyStampGuide(stampGuide);
    }

    private void verifyImage(Image image, String field) {
        require(image != null, field + " is required.");
        requireUriReference(image.url(), field + ".url");
    }

    private void verifyMapTargetIds(MapTarget target, String field) {
        if (target == null) {
            return;
        }
        requireApiId(target.mapId(), field + ".mapId");
        requireApiId(target.placeId(), field + ".placeId");
        requireApiId(target.pinId(), field + ".pinId");
        requireText(target.mapVersion(), field + ".mapVersion");
    }

    private static void requireApiId(String value, String field) {
        require(value != null && API_ID_PATTERN.matcher(value).matches(),
            field + " must match the API v2 Id pattern.");
    }

    private static void requireText(String value, String field) {
        require(value != null && !value.isBlank(), field + " must contain at least one character.");
    }

    private static void requireUriReference(String value, String field) {
        requireText(value, field);
        require(value.codePoints().allMatch(codePoint -> codePoint <= 0x7F),
            field + " must contain only ASCII URI characters.");
        try {
            new URI(value);
        } catch (URISyntaxException exception) {
            throw new CatalogIntegrityException(field + " must be a valid URI reference.");
        }
    }

    private static void requireHttpsUri(String value, String field) {
        requireUriReference(value, field);
        require(value.startsWith("https://"), field + " must start with https://.");
    }

    private static void verifyTicketGuide(TicketGuideConfig config) {
        require(config != null, "TicketGuide is required.");
        require(config.updatedAt() != null, "TicketGuide.updatedAt is required.");
        require(config.unitPriceAmount() == null || config.unitPriceAmount() >= 0,
            "TicketGuide.unitPrice must not be negative.");
        requireAllText(config.instructions(), "TicketGuide.instructions");
        // The account and transfer link are operational settings outside the
        // catalog, so a revision carries no value to validate here.
        verifyTicketSchedule(config);
    }

    private static void verifyTicketSchedule(TicketGuideConfig config) {
        boolean anySchedule = config.festivalStartDate() != null || config.festivalEndDate() != null
            || config.dailyTransferOpenTime() != null || config.dailyTransferCloseTime() != null
            || config.dailyPickupOpenTime() != null || config.dailyPickupCloseTime() != null;
        if (!anySchedule) {
            return;
        }
        require(config.hasSchedule(), CatalogExportService.LEGACY_TICKET_SCHEDULE_UNCONFIGURED
            + ": TicketGuide schedule must be complete.");
        require(!config.festivalStartDate().isAfter(config.festivalEndDate()),
            "TicketGuide festival dates are reversed.");
        require(config.dailyTransferOpenTime().isBefore(config.dailyTransferCloseTime()),
            "TicketGuide transfer times are reversed.");
        require(config.dailyPickupOpenTime().isBefore(config.dailyPickupCloseTime()),
            "TicketGuide pickup times are reversed.");
    }

    private static void verifyStampGuide(StampGuide guide) {
        require(guide != null, "StampGuide is required.");
        require(guide.updatedAt() != null, "StampGuide.updatedAt is required.");
        requireText(guide.title(), "StampGuide.title");
        requireText(guide.rewardName(), "StampGuide.reward.name");
        requireText(guide.rewardNotice(), "StampGuide.reward.notice");
        requireOptionalText(guide.rewardLocationText(), "StampGuide.reward.locationText");
        requireOptionalText(guide.rewardHoursText(), "StampGuide.reward.hoursText");
        requireOptionalText(guide.qrValue(), "StampGuide.qrValue");
        requireAllText(guide.instructions(), "StampGuide.instructions");
        for (java.time.LocalDate date : guide.dates()) {
            require(date != null, "StampGuide.dates must not contain null.");
        }
        require(new java.util.HashSet<>(guide.dates()).size() == guide.dates().size(),
            "StampGuide.dates must not contain duplicates.");
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null) {
            requireText(value, field);
        }
    }

    private static void requireAllText(List<String> values, String field) {
        require(values != null, field + " is required.");
        for (String value : values) {
            requireText(value, field);
        }
    }

    private void verifyTicketTarget(
        MapTarget target,
        List<CatalogMap> maps,
        List<Place> places,
        Map<PinKey, List<Pin>> pins
    ) {
        if (target == null) {
            return;
        }
        require(places.stream().anyMatch(place -> place.id().equals(target.placeId())),
            "Ticket map target points to a missing place.");
        requireCurrentMapTarget(target, maps, pins);
    }

    private void requireCurrentMapTarget(
        MapTarget target,
        List<CatalogMap> maps,
        Map<PinKey, List<Pin>> pins
    ) {
        CatalogMap map = maps.stream().filter(candidate -> candidate.id().equals(target.mapId())).findFirst()
            .orElseThrow(() -> new CatalogIntegrityException("Map target points to a missing map."));
        require(map.version().equals(target.mapVersion()), "Map target must use the map's current version.");
        boolean matchesPlacePin = pins.getOrDefault(new PinKey(target.mapId(), target.mapVersion()), List.of())
            .stream()
            .anyMatch(pin -> pin.id().equals(target.pinId())
                && pin.target().kind().equals("PLACE")
                && pin.target().id().equals(target.placeId()));
        require(matchesPlacePin, "Map target must point to a matching PLACE pin.");
    }

    /**
     * A map version is a geometry identity, not a revision number. The
     * revision-scoped tables retain publication history, so validate that a
     * reused version has the same image and pin geometry in every revision of
     * the same festival before exposing it.
     */
    private void verifyVersionHistory(FestivalContext context) {
        UUID festivalId = UUID.fromString(context.festivalId());
        Map<VersionKey, AssetShape> assets = new HashMap<>();
        Map<RevisionVersionKey, Map<String, PinShape>> pinSets = new LinkedHashMap<>();
        List<RevisionVersionKey> revisionVersions = new ArrayList<>();

        jdbc.query("""
            SELECT a.festival_revision_id, a.map_id, a.version,
                   a.image_url, a.image_width, a.image_height
            FROM map_asset_versions a
            JOIN festival_revisions r ON r.id = a.festival_revision_id
            WHERE r.festival_id = :festivalId
              AND (r.state IN ('published', 'archived') OR r.id = :revisionId)
            ORDER BY a.map_id, a.version, a.festival_revision_id
            """, new MapSqlParameterSource()
                .addValue("festivalId", festivalId)
                .addValue("revisionId", context.revisionId()), resultSet -> {
            RevisionVersionKey revisionVersion = new RevisionVersionKey(
                resultSet.getObject("festival_revision_id", UUID.class),
                resultSet.getString("map_id"),
                resultSet.getString("version")
            );
            revisionVersions.add(revisionVersion);
            AssetShape shape = new AssetShape(
                resultSet.getString("image_url"),
                resultSet.getInt("image_width"),
                resultSet.getInt("image_height")
            );
            AssetShape previous = assets.putIfAbsent(new VersionKey(revisionVersion.mapId(), revisionVersion.version()), shape);
            require(previous == null || previous.equals(shape),
                "A mapVersion cannot have different image data across revisions.");
        });

        jdbc.query("""
            SELECT p.festival_revision_id, p.map_id, p.map_version, p.id, p.x, p.y,
                   p.place_id, area.target_map_id
            FROM map_pins p
            JOIN festival_revisions r ON r.id = p.festival_revision_id
            LEFT JOIN map_areas area
              ON area.festival_revision_id = p.festival_revision_id
             AND area.id = p.area_id
            WHERE r.festival_id = :festivalId
              AND (r.state IN ('published', 'archived') OR r.id = :revisionId)
            ORDER BY p.map_id, p.map_version, p.festival_revision_id, p.id
            """, new MapSqlParameterSource()
                .addValue("festivalId", festivalId)
                .addValue("revisionId", context.revisionId()), resultSet -> {
            RevisionVersionKey key = new RevisionVersionKey(
                resultSet.getObject("festival_revision_id", UUID.class),
                resultSet.getString("map_id"),
                resultSet.getString("map_version")
            );
            String placeId = resultSet.getString("place_id");
            String targetKind = placeId == null ? "AREA" : "PLACE";
            String targetId = placeId == null ? resultSet.getString("target_map_id") : placeId;
            require(targetId != null, "A historical area pin must resolve to its target map.");
            pinSets.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(
                resultSet.getString("id"),
                new PinShape(
                    resultSet.getBigDecimal("x"),
                    resultSet.getBigDecimal("y"),
                    targetKind,
                    targetId
                )
            );
        });

        Map<VersionKey, Map<String, PinShape>> canonicalPinSets = new HashMap<>();
        for (RevisionVersionKey revisionVersion : revisionVersions) {
            VersionKey version = new VersionKey(revisionVersion.mapId(), revisionVersion.version());
            Map<String, PinShape> pinSet = pinSets.getOrDefault(revisionVersion, Map.of());
            Map<String, PinShape> previous = canonicalPinSets.putIfAbsent(version, pinSet);
            require(previous == null || previous.equals(pinSet),
                "A mapVersion cannot have different pin geometry or targets across revisions.");
        }
    }

    private MapSqlParameterSource parameters(FestivalContext context) {
        return parameters(context, KOREAN);
    }

    private MapSqlParameterSource parameters(FestivalContext context, String locale) {
        return new MapSqlParameterSource()
            .addValue("revisionId", context.revisionId())
            .addValue("locale", locale);
    }

    private MapTarget targetOrNull(ResultSet resultSet) throws SQLException {
        String mapId = resultSet.getString("map_id");
        String placeId = resultSet.getString("place_id");
        String pinId = resultSet.getString("pin_id");
        String mapVersion = resultSet.getString("map_version");
        if (mapId == null && placeId == null && pinId == null && mapVersion == null) {
            return null;
        }
        require(mapId != null && placeId != null && pinId != null && mapVersion != null,
            "A map target must contain all four fields.");
        return new MapTarget(mapId, placeId, pinId, mapVersion);
    }

    private static <T> Map<String, List<T>> immutableLists(Map<String, ? extends Collection<T>> source) {
        Map<String, List<T>> result = new LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static <T> Map<PinKey, List<T>> immutablePinLists(Map<PinKey, ? extends Collection<T>> source) {
        Map<PinKey, List<T>> result = new LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CatalogIntegrityException(message);
        }
    }

    private record VersionKey(String mapId, String version) {}

    private record RevisionVersionKey(UUID revisionId, String mapId, String version) {}

    private record AssetShape(String url, int width, int height) {}

    private record PinShape(BigDecimal x, BigDecimal y, String targetKind, String targetId) {}
}

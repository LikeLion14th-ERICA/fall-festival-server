package dev.espero.festival;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Validates a complete manifest before any database row is inserted. */
@Component
public final class CatalogManifestValidator {

    private static final Pattern API_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Set<String> LOCALES = Set.of("ko", "en", "zh-Hans", "ja");
    private static final Set<String> SPACE_CATEGORIES = Set.of("BOOTH", "PUB", "FLEA_MARKET");
    private static final Set<String> ARTIST_CATEGORIES = Set.of("ARTIST", "CONTEST");
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Set<String> PLACE_KINDS = Set.of("SPACE", "FACILITY", "LANDMARK");
    private static final Set<String> MAP_KINDS = Set.of("OVERVIEW", "AREA");
    private static final Set<String> FILTER_GROUPS = Set.of(
        "STUDENT_COUNCIL", "EXPERIENCE", "CONVENIENCE", "FOOD_AND_BEVERAGE", "PERFORMANCE"
    );

    public void validate(CatalogManifest manifest) {
        require(manifest != null, "manifest is required");
        require(manifest.festivalId() != null, "festivalId is required");

        Map<String, CatalogManifest.Space> spaces = unique(
            "spaces", manifest.spaces(), CatalogManifest.Space::id
        );
        Map<String, CatalogManifest.Place> places = unique(
            "places", manifest.places(), CatalogManifest.Place::id
        );
        Map<String, CatalogManifest.MapDefinition> maps = unique(
            "maps", manifest.maps(), CatalogManifest.MapDefinition::id
        );

        for (CatalogManifest.FestivalDay day : manifest.festivalDays()) {
            require(day != null && day.festivalDate() != null, "festivalDays.festivalDate is required");
            require(day.opensAt() != null && day.closesAt() != null,
                "festivalDays must contain opensAt and closesAt");
            require(day.opensAt().isBefore(day.closesAt()),
                "festivalDays.opensAt must be before closesAt");
        }
        requireUnique(manifest.festivalDays().stream().map(CatalogManifest.FestivalDay::festivalDate).toList(),
            "festivalDays.festivalDate");

        for (CatalogManifest.Space space : spaces.values()) {
            id(space.id(), "spaces.id");
            require(SPACE_CATEGORIES.contains(space.category()), "Unsupported space category: " + space.category());
            image(space.imageUrl(), space.imageWidth(), space.imageHeight(), "spaces.image");
        }
        requireTranslations(
            manifest.spaceTranslations(), "spaceTranslations", CatalogManifest.SpaceTranslation::spaceId,
            spaces.keySet(), true
        );
        validateSpaceTranslations(manifest.spaceTranslations(), spaces.keySet());
        validateSortOrders(manifest.spaceSortOrders(), spaces.keySet());

        for (CatalogManifest.SpaceEvent event : manifest.spaceEvents()) {
            require(event != null && spaces.containsKey(event.spaceId()),
                "spaceEvents references an unknown space");
            locale(event.locale());
            require(event.sortOrder() > 0, "spaceEvents.sortOrder must be positive");
            text(event.content(), "spaceEvents.content");
            require("BOOTH".equals(spaces.get(event.spaceId()).category()),
                "Only BOOTH spaces can contain events");
        }
        requireUnique(manifest.spaceEvents().stream().map(e -> key(e.spaceId(), e.locale(), e.sortOrder())).toList(),
            "spaceEvents");

        for (CatalogManifest.SpaceMenuItem item : manifest.spaceMenuItems()) {
            require(item != null && spaces.containsKey(item.spaceId()),
                "spaceMenuItems references an unknown space");
            locale(item.locale());
            require(item.sortOrder() > 0, "spaceMenuItems.sortOrder must be positive");
            text(item.name(), "spaceMenuItems.name");
            require(item.priceAmount() >= 0, "spaceMenuItems.priceAmount must be non-negative");
            require("PUB".equals(spaces.get(item.spaceId()).category()),
                "Only PUB spaces can contain menu items");
        }
        requireUnique(manifest.spaceMenuItems().stream().map(e -> key(e.spaceId(), e.locale(), e.sortOrder())).toList(),
            "spaceMenuItems");

        for (CatalogManifest.Place place : places.values()) {
            id(place.id(), "places.id");
            require(PLACE_KINDS.contains(place.kind()), "Unsupported place kind: " + place.kind());
            if ("SPACE".equals(place.kind())) {
                require(place.spaceId() != null && spaces.containsKey(place.spaceId()),
                    "SPACE place must reference an existing space");
            } else {
                require(place.spaceId() == null, "Non-SPACE place must not reference a space");
            }
        }
        requireUnique(
            manifest.places().stream().filter(p -> p.spaceId() != null).map(CatalogManifest.Place::spaceId).toList(),
            "places.spaceId"
        );
        requireTranslations(
            manifest.placeTranslations(), "placeTranslations", CatalogManifest.PlaceTranslation::placeId,
            places.keySet(), false
        );

        for (CatalogManifest.MapDefinition map : maps.values()) {
            id(map.id(), "maps.id");
            require(MAP_KINDS.contains(map.kind()), "Unsupported map kind: " + map.kind());
            require(map.sortRank() > 0, "maps.sortRank must be positive");
            text(map.currentVersion(), "maps.currentVersion");
        }
        requireUnique(manifest.maps().stream().map(CatalogManifest.MapDefinition::sortRank).toList(), "maps.sortRank");
        requireTranslations(
            manifest.mapTranslations(), "mapTranslations", CatalogManifest.MapTranslation::mapId,
            maps.keySet(), true
        );

        Map<String, String> currentVersions = new HashMap<>();
        maps.values().forEach(map -> currentVersions.put(map.id(), map.currentVersion()));
        Set<String> assets = new HashSet<>();
        for (CatalogManifest.MapAsset asset : manifest.mapAssets()) {
            require(asset != null && maps.containsKey(asset.mapId()), "mapAssets references an unknown map");
            text(asset.version(), "mapAssets.version");
            require(assets.add(key(asset.mapId(), asset.version())), "Duplicate mapAssets row");
            image(asset.imageUrl(), asset.imageWidth(), asset.imageHeight(), "mapAssets.image");
            text(asset.imageAlt(), "mapAssets.imageAlt");
        }
        for (Map.Entry<String, String> current : currentVersions.entrySet()) {
            require(assets.contains(key(current.getKey(), current.getValue())),
                "Current map version has no asset: " + current.getKey());
        }

        Map<String, CatalogManifest.MapArea> areas = unique(
            "mapAreas", manifest.mapAreas(), CatalogManifest.MapArea::id
        );
        for (CatalogManifest.MapArea area : areas.values()) {
            id(area.id(), "mapAreas.id");
            require(maps.containsKey(area.targetMapId()), "mapAreas references an unknown target map");
        }

        Map<String, CatalogManifest.MapPin> pins = new HashMap<>();
        for (CatalogManifest.MapPin pin : manifest.mapPins()) {
            require(pin != null && maps.containsKey(pin.mapId()), "mapPins references an unknown map");
            require(currentVersions.get(pin.mapId()).equals(pin.mapVersion()),
                "mapPins must use the map currentVersion");
            id(pin.id(), "mapPins.id");
            text(pin.category(), "mapPins.category");
            require(pin.x() != null && pin.y() != null
                    && pin.x().signum() >= 0 && pin.x().compareTo(java.math.BigDecimal.ONE) <= 0
                    && pin.y().signum() >= 0 && pin.y().compareTo(java.math.BigDecimal.ONE) <= 0,
                "mapPins coordinates must be between 0 and 1");
            require((pin.placeId() == null) != (pin.areaId() == null),
                "mapPins must have exactly one target");
            if (pin.placeId() != null) {
                require(places.containsKey(pin.placeId()), "mapPins references an unknown place");
                require(pin.filterGroup() != null && FILTER_GROUPS.contains(pin.filterGroup()),
                    "PLACE mapPins require a supported filterGroup");
            } else {
                require(areas.containsKey(pin.areaId()), "mapPins references an unknown area");
                require(pin.filterGroup() == null, "AREA mapPins must not have a filterGroup");
            }
            require(pins.put(key(pin.mapId(), pin.mapVersion(), pin.id()), pin) == null,
                "Duplicate mapPins row");
        }

        requireTranslations(
            manifest.mapPinTranslations(), "mapPinTranslations", t -> key(t.mapId(), t.mapVersion(), t.pinId()),
            pins.keySet(), true
        );
        Map<String, Set<String>> groupLocales = new HashMap<>();
        for (CatalogManifest.MapPinFilterGroupTranslation translation
            : manifest.mapPinFilterGroupTranslations()) {
            require(translation != null && FILTER_GROUPS.contains(translation.filterGroup()),
                "Unsupported map pin filter group");
            locale(translation.locale());
            text(translation.label(), "mapPinFilterGroupTranslations.label");
            Set<String> locales = groupLocales.computeIfAbsent(translation.filterGroup(), ignored -> new HashSet<>());
            require(locales.add(translation.locale()), "Duplicate map pin filter group translation");
        }
        Set<String> usedGroups = manifest.mapPins().stream()
            .map(CatalogManifest.MapPin::filterGroup)
            .filter(group -> group != null)
            .collect(java.util.stream.Collectors.toSet());
        for (String group : usedGroups) {
            require(groupLocales.getOrDefault(group, Set.of()).contains("ko"),
                "Every used filter group needs a Korean label");
        }

        Map<String, CatalogManifest.SpaceMapTarget> targets = unique(
            "spaceMapTargets", manifest.spaceMapTargets(), CatalogManifest.SpaceMapTarget::spaceId
        );
        for (CatalogManifest.SpaceMapTarget target : targets.values()) {
            require(spaces.containsKey(target.spaceId()), "spaceMapTargets references an unknown space");
            require(maps.containsKey(target.mapId()) && "AREA".equals(maps.get(target.mapId()).kind()),
                "spaceMapTargets must point to an AREA map");
            require(currentVersions.get(target.mapId()).equals(target.mapVersion()),
                "spaceMapTargets must use the map currentVersion");
            CatalogManifest.Place place = places.get(target.placeId());
            require(place != null && target.spaceId().equals(place.spaceId()),
                "spaceMapTargets must point to the same space place");
            require(pins.containsKey(key(target.mapId(), target.mapVersion(), target.pinId())),
                "spaceMapTargets references an unknown pin");
            CatalogManifest.MapPin pin = pins.get(key(target.mapId(), target.mapVersion(), target.pinId()));
            require(target.placeId().equals(pin.placeId()), "spaceMapTargets pin must target its place");
        }

        validatePerformanceCatalog(manifest);

        validateTicketGuide(manifest.ticketGuide(), maps, places, pins, currentVersions);
        validateStampGuide(manifest.stampGuide());
    }

    private void validatePerformanceCatalog(CatalogManifest manifest) {
        Map<String, CatalogManifest.Artist> artists = unique(
            "artists", manifest.artists(), CatalogManifest.Artist::id
        );
        for (CatalogManifest.Artist artist : artists.values()) {
            id(artist.id(), "artists.id");
            require(ARTIST_CATEGORIES.contains(artist.category()),
                "Unsupported artist category: " + artist.category());
            image(artist.imageUrl(), artist.imageWidth(), artist.imageHeight(), "artists.image");
        }

        Set<String> artistTranslationKeys = new HashSet<>();
        Set<String> koreanArtists = new HashSet<>();
        for (CatalogManifest.ArtistTranslation translation : manifest.artistTranslations()) {
            require(translation != null && artists.containsKey(translation.artistId()),
                "artistTranslations references an unknown artist");
            locale(translation.locale());
            text(translation.name(), "artistTranslations.name");
            text(translation.imageAlt(), "artistTranslations.imageAlt");
            optionalText(translation.introduction(), "artistTranslations.introduction");
            require(artistTranslationKeys.add(key(translation.artistId(), translation.locale())),
                "Duplicate artistTranslations row");
            if ("ko".equals(translation.locale())) {
                koreanArtists.add(translation.artistId());
            }
        }
        require(koreanArtists.equals(artists.keySet()),
            "Every artist needs a Korean artistTranslations row");

        Set<String> artistLinks = new HashSet<>();
        for (CatalogManifest.ArtistLink link : manifest.artistLinks()) {
            require(link != null && artists.containsKey(link.artistId()),
                "artistLinks references an unknown artist");
            require(link.sortOrder() > 0, "artistLinks.sortOrder must be positive");
            https(link.url(), "artistLinks.url");
            require(artistLinks.add(key(link.artistId(), link.sortOrder())),
                "Duplicate artistLinks row");
        }
        validateArtistLinkTranslations(manifest.artistLinkTranslations(), artistLinks);

        Set<String> artistSongs = new HashSet<>();
        for (CatalogManifest.ArtistSong song : manifest.artistSongs()) {
            require(song != null && artists.containsKey(song.artistId()),
                "artistSongs references an unknown artist");
            require(song.sortOrder() >= 1 && song.sortOrder() <= 3,
                "artistSongs.sortOrder must be between 1 and 3");
            https(song.url(), "artistSongs.url");
            require(artistSongs.add(key(song.artistId(), song.sortOrder())),
                "Duplicate artistSongs row");
        }
        validateArtistSongTranslations(manifest.artistSongTranslations(), artistSongs);

        Set<java.time.LocalDate> festivalDays = manifest.festivalDays().stream()
            .map(CatalogManifest.FestivalDay::festivalDate)
            .collect(java.util.stream.Collectors.toSet());
        Map<String, CatalogManifest.Performance> performances = unique(
            "performances", manifest.performances(), CatalogManifest.Performance::id
        );
        for (CatalogManifest.Performance performance : performances.values()) {
            id(performance.id(), "performances.id");
            require(performance.festivalDate() != null && festivalDays.contains(performance.festivalDate()),
                "performances references an unknown festival day");
            require(performance.startsAt() != null && performance.endsAt() != null,
                "performances must contain startsAt and endsAt");
            require(performance.startsAt().isBefore(performance.endsAt()),
                "performances.startsAt must be before endsAt");
            require(performance.startsAt().atZoneSameInstant(KOREA).toLocalDate()
                    .equals(performance.festivalDate()),
                "performances.startsAt must fall on festivalDate in Asia/Seoul");
        }
        validatePerformanceTranslations(manifest.performanceTranslations(), performances.keySet());

        Set<String> performanceArtists = new HashSet<>();
        Set<String> displayOrders = new HashSet<>();
        for (CatalogManifest.PerformanceArtist row : manifest.performanceArtists()) {
            require(row != null && performances.containsKey(row.performanceId()),
                "performanceArtists references an unknown performance");
            require(artists.containsKey(row.artistId()),
                "performanceArtists references an unknown artist");
            require(row.displayOrder() > 0, "performanceArtists.displayOrder must be positive");
            require(performanceArtists.add(key(row.performanceId(), row.artistId())),
                "Duplicate performanceArtists artist");
            require(displayOrders.add(key(row.performanceId(), row.displayOrder())),
                "Duplicate performanceArtists displayOrder");
        }

        CatalogManifest.TimetableConfig timetable = manifest.timetableConfig();
        if (timetable != null) {
            require(timetable.axisStartTime() != null && timetable.axisEndTime() != null,
                "timetableConfig must contain axisStartTime and axisEndTime");
            require(timetable.axisStartTime().isBefore(timetable.axisEndTime()),
                "timetableConfig.axisStartTime must be before axisEndTime");
        }

        Map<String, CatalogManifest.ProhibitedItem> prohibitedItems = unique(
            "prohibitedItems", manifest.prohibitedItems(), CatalogManifest.ProhibitedItem::id
        );
        Set<Integer> itemSortOrders = new HashSet<>();
        for (CatalogManifest.ProhibitedItem item : prohibitedItems.values()) {
            id(item.id(), "prohibitedItems.id");
            require(item.sortOrder() > 0, "prohibitedItems.sortOrder must be positive");
            require(itemSortOrders.add(item.sortOrder()), "Duplicate prohibitedItems.sortOrder");
        }
        validateProhibitedItemTranslations(
            manifest.prohibitedItemTranslations(), prohibitedItems.keySet()
        );

        Set<String> messageLocales = new HashSet<>();
        for (CatalogManifest.ProhibitedMessage message : manifest.prohibitedMessages()) {
            require(message != null, "prohibitedMessages contains a null row");
            locale(message.locale());
            text(message.message(), "prohibitedMessages.message");
            require(messageLocales.add(message.locale()), "Duplicate prohibitedMessages locale");
        }
        require(messageLocales.isEmpty() || messageLocales.contains("ko"),
            "Non-empty prohibitedMessages needs a Korean row");
    }

    private void validateArtistLinkTranslations(
        List<CatalogManifest.ArtistLinkTranslation> rows,
        Set<String> links
    ) {
        Set<String> keys = new HashSet<>();
        Set<String> korean = new HashSet<>();
        for (CatalogManifest.ArtistLinkTranslation row : rows) {
            String link = row == null ? null : key(row.artistId(), row.sortOrder());
            require(row != null && links.contains(link),
                "artistLinkTranslations references an unknown artist link");
            locale(row.locale());
            text(row.label(), "artistLinkTranslations.label");
            require(keys.add(key(link, row.locale())), "Duplicate artistLinkTranslations row");
            if ("ko".equals(row.locale())) {
                korean.add(link);
            }
        }
        require(korean.equals(links), "Every artist link needs a Korean translation");
    }

    private void validateArtistSongTranslations(
        List<CatalogManifest.ArtistSongTranslation> rows,
        Set<String> songs
    ) {
        Set<String> keys = new HashSet<>();
        Set<String> korean = new HashSet<>();
        for (CatalogManifest.ArtistSongTranslation row : rows) {
            String song = row == null ? null : key(row.artistId(), row.sortOrder());
            require(row != null && songs.contains(song),
                "artistSongTranslations references an unknown artist song");
            locale(row.locale());
            text(row.title(), "artistSongTranslations.title");
            require(keys.add(key(song, row.locale())), "Duplicate artistSongTranslations row");
            if ("ko".equals(row.locale())) {
                korean.add(song);
            }
        }
        require(korean.equals(songs), "Every artist song needs a Korean translation");
    }

    private void validatePerformanceTranslations(
        List<CatalogManifest.PerformanceTranslation> rows,
        Set<String> performances
    ) {
        Set<String> keys = new HashSet<>();
        Set<String> korean = new HashSet<>();
        for (CatalogManifest.PerformanceTranslation row : rows) {
            require(row != null && performances.contains(row.performanceId()),
                "performanceTranslations references an unknown performance");
            locale(row.locale());
            text(row.title(), "performanceTranslations.title");
            optionalText(row.description(), "performanceTranslations.description");
            require(keys.add(key(row.performanceId(), row.locale())),
                "Duplicate performanceTranslations row");
            if ("ko".equals(row.locale())) {
                korean.add(row.performanceId());
            }
        }
        require(korean.equals(performances), "Every performance needs a Korean translation");
    }

    private void validateProhibitedItemTranslations(
        List<CatalogManifest.ProhibitedItemTranslation> rows,
        Set<String> items
    ) {
        Set<String> keys = new HashSet<>();
        Set<String> korean = new HashSet<>();
        for (CatalogManifest.ProhibitedItemTranslation row : rows) {
            require(row != null && items.contains(row.itemId()),
                "prohibitedItemTranslations references an unknown prohibited item");
            locale(row.locale());
            text(row.label(), "prohibitedItemTranslations.label");
            require(keys.add(key(row.itemId(), row.locale())),
                "Duplicate prohibitedItemTranslations row");
            if ("ko".equals(row.locale())) {
                korean.add(row.itemId());
            }
        }
        require(korean.equals(items), "Every prohibited item needs a Korean translation");
    }

    private void validateSpaceTranslations(List<CatalogManifest.SpaceTranslation> translations, Set<String> spaces) {
        for (CatalogManifest.SpaceTranslation translation : translations) {
            require(translation != null && spaces.contains(translation.spaceId()),
                "spaceTranslations references an unknown space");
            locale(translation.locale());
            text(translation.name(), "spaceTranslations.name");
            text(translation.imageAlt(), "spaceTranslations.imageAlt");
            text(translation.locationText(), "spaceTranslations.locationText");
            optionalText(translation.operatorText(), "spaceTranslations.operatorText");
            optionalText(translation.hoursText(), "spaceTranslations.hoursText");
            optionalText(translation.descriptionText(), "spaceTranslations.descriptionText");
            optionalText(translation.experienceText(), "spaceTranslations.experienceText");
            require((translation.contactLabel() == null) == (translation.contactUrl() == null),
                "spaceTranslations contact label and URL must be supplied together");
            if (translation.contactUrl() != null) {
                https(translation.contactUrl(), "spaceTranslations.contactUrl");
                text(translation.contactLabel(), "spaceTranslations.contactLabel");
            }
        }
    }

    private void validateSortOrders(List<CatalogManifest.SpaceSortOrder> rows, Set<String> spaces) {
        Set<String> keys = new HashSet<>();
        Set<String> koreanSpaces = new HashSet<>();
        Set<Integer> koreanRanks = new HashSet<>();
        for (CatalogManifest.SpaceSortOrder row : rows) {
            require(row != null && spaces.contains(row.spaceId()), "spaceSortOrders references an unknown space");
            locale(row.locale());
            require(row.sortRank() > 0, "spaceSortOrders.sortRank must be positive");
            require(keys.add(key(row.locale(), row.spaceId())), "Duplicate spaceSortOrders row");
            if ("ko".equals(row.locale())) {
                koreanSpaces.add(row.spaceId());
                require(koreanRanks.add(row.sortRank()), "Duplicate Korean space sort rank");
            }
        }
        require(koreanSpaces.equals(spaces), "Every space needs a Korean sort rank");
    }

    private void validateTicketGuide(
        CatalogManifest.TicketGuide guide,
        Map<String, CatalogManifest.MapDefinition> maps,
        Map<String, CatalogManifest.Place> places,
        Map<String, CatalogManifest.MapPin> pins,
        Map<String, String> currentVersions
    ) {
        require(guide != null, "ticketGuide is required");
        require(guide.unitPriceAmount() == null || guide.unitPriceAmount() >= 0,
            "ticketGuide.unitPriceAmount must be non-negative");
        for (String instruction : guide.instructions()) {
            text(instruction, "ticketGuide.instructions");
        }
        boolean schedule = guide.festivalStartDate() != null || guide.festivalEndDate() != null
            || guide.dailyTransferOpenTime() != null || guide.dailyTransferCloseTime() != null
            || guide.dailyPickupOpenTime() != null || guide.dailyPickupCloseTime() != null;
        boolean completeSchedule = guide.festivalStartDate() != null && guide.festivalEndDate() != null
            && guide.dailyTransferOpenTime() != null && guide.dailyTransferCloseTime() != null
            && guide.dailyPickupOpenTime() != null && guide.dailyPickupCloseTime() != null;
        require(!schedule || completeSchedule, "ticketGuide schedule fields must be complete");
        if (completeSchedule) {
            require(!guide.festivalStartDate().isAfter(guide.festivalEndDate()),
                "ticketGuide festival dates are reversed");
            require(guide.dailyTransferOpenTime().isBefore(guide.dailyTransferCloseTime()),
                "ticketGuide transfer times are reversed");
            require(guide.dailyPickupOpenTime().isBefore(guide.dailyPickupCloseTime()),
                "ticketGuide pickup times are reversed");
        }
        target(guide.mapId(), guide.placeId(), guide.pinId(), guide.mapVersion(),
            maps, places, pins, currentVersions, "ticketGuide.mapTarget");
    }

    private void validateStampGuide(CatalogManifest.StampGuide guide) {
        require(guide != null, "stampGuide is required");
        text(guide.title(), "stampGuide.title");
        text(guide.rewardName(), "stampGuide.rewardName");
        optionalText(guide.rewardLocationText(), "stampGuide.rewardLocationText");
        optionalText(guide.rewardHoursText(), "stampGuide.rewardHoursText");
        text(guide.rewardNotice(), "stampGuide.rewardNotice");
        optionalText(guide.qrValue(), "stampGuide.qrValue");
        for (String instruction : guide.instructions()) {
            text(instruction, "stampGuide.instructions");
        }
        requireUnique(guide.dates(), "stampGuide.dates");
    }

    private void target(
        String mapId,
        String placeId,
        String pinId,
        String mapVersion,
        Map<String, CatalogManifest.MapDefinition> maps,
        Map<String, CatalogManifest.Place> places,
        Map<String, CatalogManifest.MapPin> pins,
        Map<String, String> currentVersions,
        String field
    ) {
        boolean any = mapId != null || placeId != null || pinId != null || mapVersion != null;
        if (!any) {
            return;
        }
        require(mapId != null && placeId != null && pinId != null && mapVersion != null,
            field + " must contain all four fields");
        require(maps.containsKey(mapId), field + " references an unknown map");
        require(places.containsKey(placeId), field + " references an unknown place");
        require(currentVersions.get(mapId).equals(mapVersion), field + " must use the current map version");
        CatalogManifest.MapPin pin = pins.get(key(mapId, mapVersion, pinId));
        require(pin != null && placeId.equals(pin.placeId()), field + " must point to a matching PLACE pin");
    }

    private <T> void requireTranslations(
        List<T> rows,
        String field,
        Function<T, String> keyFunction,
        Set<String> expected,
        boolean requireName
    ) {
        Set<String> keys = new HashSet<>();
        Set<String> korean = new HashSet<>();
        for (T row : rows) {
            require(row != null, field + " contains a null row");
            String key = keyFunction.apply(row);
            require(key != null && expected.contains(key), field + " references an unknown entity");
            String rowLocale = localeOf(row, field);
            locale(rowLocale);
            require(keys.add(key + "|" + rowLocale), "Duplicate " + field + " row");
            if ("ko".equals(rowLocale)) {
                korean.add(key);
            }
            if (requireName) {
                String name = nameOf(row, field);
                text(name, field + ".name/label");
            }
        }
        require(korean.equals(expected), "Every entity needs a Korean " + field + " row");
    }

    private String localeOf(Object row, String field) {
        return switch (field) {
            case "spaceTranslations" -> ((CatalogManifest.SpaceTranslation) row).locale();
            case "placeTranslations" -> ((CatalogManifest.PlaceTranslation) row).locale();
            case "mapTranslations" -> ((CatalogManifest.MapTranslation) row).locale();
            case "mapPinTranslations" -> ((CatalogManifest.MapPinTranslation) row).locale();
            default -> throw new IllegalArgumentException("Unsupported translation field: " + field);
        };
    }

    private String nameOf(Object row, String field) {
        return switch (field) {
            case "spaceTranslations" -> ((CatalogManifest.SpaceTranslation) row).name();
            case "placeTranslations" -> ((CatalogManifest.PlaceTranslation) row).name();
            case "mapTranslations" -> ((CatalogManifest.MapTranslation) row).name();
            case "mapPinTranslations" -> ((CatalogManifest.MapPinTranslation) row).label();
            default -> throw new IllegalArgumentException("Unsupported translation field: " + field);
        };
    }

    private <T> Map<String, T> unique(String field, List<T> rows, Function<T, String> keyFunction) {
        Map<String, T> result = new HashMap<>();
        for (T row : rows) {
            require(row != null, field + " contains a null row");
            String key = keyFunction.apply(row);
            require(key != null, field + " id is required");
            require(result.put(key, row) == null, "Duplicate " + field + " id: " + key);
        }
        return result;
    }

    private void locale(String value) {
        require(value != null && LOCALES.contains(value), "Unsupported locale: " + value);
    }

    private void id(String value, String field) {
        require(value != null && API_ID.matcher(value).matches(), field + " must be a valid API id");
    }

    private void image(String url, int width, int height, String field) {
        uri(url, field + ".url");
        require(width > 0 && height > 0, field + " dimensions must be positive");
    }

    private void uri(String value, String field) {
        text(value, field);
        require(value.codePoints().allMatch(c -> c <= 0x7f), field + " must contain ASCII URI characters");
        try {
            new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(field + " must be a valid URI reference", exception);
        }
    }

    private void https(String value, String field) {
        uri(value, field);
        require(value.startsWith("https://"), field + " must use https://");
    }

    private void text(String value, String field) {
        require(value != null && !value.isBlank(), field + " must not be blank");
    }

    private void optionalText(String value, String field) {
        if (value != null) {
            text(value, field);
        }
    }

    private void requireUnique(List<?> values, String field) {
        Set<?> unique = new HashSet<>(values);
        require(unique.size() == values.size(), "Duplicate " + field + " value");
    }

    private static String key(Object... values) {
        return java.util.Arrays.stream(values).map(String::valueOf).reduce((a, b) -> a + "|" + b).orElse("");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}

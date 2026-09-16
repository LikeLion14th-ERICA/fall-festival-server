package dev.espero.festival;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A complete, typed catalog revision input. It intentionally contains no
 * datasource settings, receipt verification code, SQL, or remote download instruction.
 * Jackson rejects fields outside this shape in CatalogManifestReader.
 */
public record CatalogManifest(
    UUID festivalId,
    List<FestivalDay> festivalDays,
    List<Space> spaces,
    List<SpaceTranslation> spaceTranslations,
    List<SpaceSortOrder> spaceSortOrders,
    List<SpaceEvent> spaceEvents,
    List<SpaceMenuItem> spaceMenuItems,
    List<Place> places,
    List<PlaceTranslation> placeTranslations,
    List<MapDefinition> maps,
    List<MapTranslation> mapTranslations,
    List<MapAsset> mapAssets,
    List<MapArea> mapAreas,
    List<MapPin> mapPins,
    List<MapPinTranslation> mapPinTranslations,
    List<MapPinFilterGroupTranslation> mapPinFilterGroupTranslations,
    List<SpaceMapTarget> spaceMapTargets,
    TicketGuide ticketGuide,
    StampGuide stampGuide
) {

    public CatalogManifest {
        festivalDays = required("festivalDays", festivalDays);
        spaces = required("spaces", spaces);
        spaceTranslations = required("spaceTranslations", spaceTranslations);
        spaceSortOrders = required("spaceSortOrders", spaceSortOrders);
        spaceEvents = required("spaceEvents", spaceEvents);
        spaceMenuItems = required("spaceMenuItems", spaceMenuItems);
        places = required("places", places);
        placeTranslations = required("placeTranslations", placeTranslations);
        maps = required("maps", maps);
        mapTranslations = required("mapTranslations", mapTranslations);
        mapAssets = required("mapAssets", mapAssets);
        mapAreas = required("mapAreas", mapAreas);
        mapPins = required("mapPins", mapPins);
        mapPinTranslations = required("mapPinTranslations", mapPinTranslations);
        mapPinFilterGroupTranslations = required(
            "mapPinFilterGroupTranslations", mapPinFilterGroupTranslations
        );
        spaceMapTargets = required("spaceMapTargets", spaceMapTargets);
    }

    private static <T> List<T> required(String field, List<T> value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return List.copyOf(value);
    }

    private static <T> List<T> optionalList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    public record FestivalDay(
        LocalDate festivalDate,
        OffsetDateTime opensAt,
        OffsetDateTime closesAt
    ) {}

    public record Space(
        String id,
        String category,
        String imageUrl,
        int imageWidth,
        int imageHeight
    ) {}

    public record SpaceTranslation(
        String spaceId,
        String locale,
        String name,
        String imageAlt,
        String locationText,
        String operatorText,
        String hoursText,
        String descriptionText,
        String experienceText,
        String contactLabel,
        String contactUrl
    ) {}

    public record SpaceSortOrder(
        String locale,
        String spaceId,
        int sortRank
    ) {}

    public record SpaceEvent(
        String spaceId,
        String locale,
        int sortOrder,
        String content
    ) {}

    public record SpaceMenuItem(
        String spaceId,
        String locale,
        int sortOrder,
        String name,
        int priceAmount
    ) {}

    public record Place(
        String id,
        String kind,
        String spaceId
    ) {}

    public record PlaceTranslation(
        String placeId,
        String locale,
        String name,
        String locationText,
        String hoursText,
        String descriptionText,
        String usageText
    ) {}

    public record MapDefinition(
        String id,
        String kind,
        int sortRank,
        String currentVersion
    ) {}

    public record MapTranslation(
        String mapId,
        String locale,
        String name
    ) {}

    public record MapAsset(
        String mapId,
        String version,
        String imageUrl,
        String imageAlt,
        int imageWidth,
        int imageHeight
    ) {}

    public record MapArea(
        String id,
        String targetMapId
    ) {}

    public record MapPin(
        String mapId,
        String mapVersion,
        String id,
        String category,
        String filterGroup,
        BigDecimal x,
        BigDecimal y,
        String placeId,
        String areaId
    ) {}

    public record MapPinTranslation(
        String mapId,
        String mapVersion,
        String pinId,
        String locale,
        String label
    ) {}

    public record MapPinFilterGroupTranslation(
        String filterGroup,
        String locale,
        String label
    ) {}

    public record SpaceMapTarget(
        String spaceId,
        String mapId,
        String mapVersion,
        String pinId,
        String placeId
    ) {}

    public record TicketGuide(
        Integer unitPriceAmount,
        String accountBankName,
        String accountNumber,
        String accountHolder,
        String transferLinkLabel,
        String transferLinkUrl,
        String mapId,
        String placeId,
        String pinId,
        String mapVersion,
        List<String> instructions,
        LocalDate festivalStartDate,
        LocalDate festivalEndDate,
        LocalTime dailyTransferOpenTime,
        LocalTime dailyTransferCloseTime,
        LocalTime dailyPickupOpenTime,
        LocalTime dailyPickupCloseTime
    ) {
        public TicketGuide {
            instructions = optionalList(instructions);
        }
    }

    public record StampGuide(
        String title,
        List<LocalDate> dates,
        List<String> instructions,
        String rewardName,
        String rewardLocationText,
        String rewardHoursText,
        String rewardNotice,
        String qrValue
    ) {
        public StampGuide {
            dates = optionalList(dates);
            instructions = optionalList(instructions);
        }
    }
}

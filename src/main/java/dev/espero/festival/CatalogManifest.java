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
 *
 * <p>{@code baselineRevisionId} is the published revision this content was
 * edited from. Only the first catalog of a festival may leave it null, and an
 * import whose baseline no longer matches the current published revision is
 * refused.</p>
 */
public record CatalogManifest(
    UUID festivalId,
    UUID baselineRevisionId,
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
    List<Artist> artists,
    List<ArtistTranslation> artistTranslations,
    List<ArtistLink> artistLinks,
    List<ArtistLinkTranslation> artistLinkTranslations,
    List<ArtistSong> artistSongs,
    List<ArtistSongTranslation> artistSongTranslations,
    List<Performance> performances,
    List<PerformanceTranslation> performanceTranslations,
    List<PerformanceArtist> performanceArtists,
    TimetableConfig timetableConfig,
    List<ProhibitedItem> prohibitedItems,
    List<ProhibitedItemTranslation> prohibitedItemTranslations,
    List<ProhibitedMessage> prohibitedMessages,
    TicketGuide ticketGuide,
    StampGuide stampGuide,
    List<FestivalLink> festivalLinks,
    List<FestivalLinkTranslation> festivalLinkTranslations,
    List<FestivalTitleTranslation> festivalTitleTranslations,
    List<MapAssetTranslation> mapAssetTranslations,
    List<TicketGuideTranslation> ticketGuideTranslations,
    List<StampGuideTranslation> stampGuideTranslations,
    List<StampBooth> stampBooths,
    List<StampBoothToken> stampBoothTokens
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
        artists = required("artists", artists);
        artistTranslations = required("artistTranslations", artistTranslations);
        artistLinks = required("artistLinks", artistLinks);
        artistLinkTranslations = required("artistLinkTranslations", artistLinkTranslations);
        artistSongs = required("artistSongs", artistSongs);
        artistSongTranslations = required("artistSongTranslations", artistSongTranslations);
        performances = required("performances", performances);
        performanceTranslations = required("performanceTranslations", performanceTranslations);
        performanceArtists = required("performanceArtists", performanceArtists);
        prohibitedItems = required("prohibitedItems", prohibitedItems);
        prohibitedItemTranslations = required("prohibitedItemTranslations", prohibitedItemTranslations);
        prohibitedMessages = required("prohibitedMessages", prohibitedMessages);
        // Home links arrived after the first manifests, which may omit them.
        festivalLinks = optionalList(festivalLinks);
        festivalLinkTranslations = optionalList(festivalLinkTranslations);
        // Non-Korean text for single-language parts; Korean stays in the base rows.
        festivalTitleTranslations = optionalList(festivalTitleTranslations);
        mapAssetTranslations = optionalList(mapAssetTranslations);
        ticketGuideTranslations = optionalList(ticketGuideTranslations);
        stampGuideTranslations = optionalList(stampGuideTranslations);
        // Booth stamps arrived after the first manifests, which may omit them.
        stampBooths = optionalList(stampBooths);
        stampBoothTokens = optionalList(stampBoothTokens);
    }

    /** Manifest without home links; they default to empty. */
    public CatalogManifest(
        UUID festivalId,
        UUID baselineRevisionId,
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
        List<Artist> artists,
        List<ArtistTranslation> artistTranslations,
        List<ArtistLink> artistLinks,
        List<ArtistLinkTranslation> artistLinkTranslations,
        List<ArtistSong> artistSongs,
        List<ArtistSongTranslation> artistSongTranslations,
        List<Performance> performances,
        List<PerformanceTranslation> performanceTranslations,
        List<PerformanceArtist> performanceArtists,
        TimetableConfig timetableConfig,
        List<ProhibitedItem> prohibitedItems,
        List<ProhibitedItemTranslation> prohibitedItemTranslations,
        List<ProhibitedMessage> prohibitedMessages,
        TicketGuide ticketGuide,
        StampGuide stampGuide
    ) {
        this(
            festivalId,
            baselineRevisionId,
            festivalDays,
            spaces,
            spaceTranslations,
            spaceSortOrders,
            spaceEvents,
            spaceMenuItems,
            places,
            placeTranslations,
            maps,
            mapTranslations,
            mapAssets,
            mapAreas,
            mapPins,
            mapPinTranslations,
            mapPinFilterGroupTranslations,
            spaceMapTargets,
            artists,
            artistTranslations,
            artistLinks,
            artistLinkTranslations,
            artistSongs,
            artistSongTranslations,
            performances,
            performanceTranslations,
            performanceArtists,
            timetableConfig,
            prohibitedItems,
            prohibitedItemTranslations,
            prohibitedMessages,
            ticketGuide,
            stampGuide,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
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

    /**
     * A home link. {@code UNIVERSITY_NOTICES}, {@code FAQ} and
     * {@code WELCOME_DAY} appear at most once; {@code OFFICIAL_CHANNEL} rows are
     * ordered by {@code sortOrder} and carry the frontend icon key.
     */
    public record FestivalLink(
        String id,
        String kind,
        String url,
        String iconKey,
        int sortOrder
    ) {}

    public record FestivalLinkTranslation(
        String linkId,
        String locale,
        String label
    ) {}

    /** The festival title in a non-Korean locale; Korean is the festival's own title. */
    public record FestivalTitleTranslation(
        String locale,
        String title
    ) {}

    /** Map image alt text in a non-Korean locale. */
    public record MapAssetTranslation(
        String mapId,
        String version,
        String locale,
        String imageAlt
    ) {}

    /** Ticket guide instructions in a non-Korean locale, one per Korean instruction. */
    public record TicketGuideTranslation(
        String locale,
        List<String> instructions
    ) {
        public TicketGuideTranslation {
            instructions = optionalList(instructions);
        }
    }

    /**
     * Stamp guide text in a non-Korean locale. Optional fields are present
     * exactly when the Korean guide has them.
     */
    public record StampGuideTranslation(
        String locale,
        String title,
        List<String> instructions,
        String rewardName,
        String rewardLocationText,
        String rewardHoursText,
        String rewardNotice
    ) {
        public StampGuideTranslation {
            instructions = optionalList(instructions);
        }
    }

    /** A booth that shows its own stamp QR (STAMP-001). */
    public record StampBooth(
        String id,
        String name,
        int sortOrder
    ) {}

    /**
     * The SHA-256 (lowercase hex) of the random token in a booth's QR link.
     * {@code validDate} null means every festival day; a date limits the
     * token to that day.
     */
    public record StampBoothToken(
        String boothId,
        java.time.LocalDate validDate,
        String tokenSha256
    ) {}

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

    public record Artist(
        String id,
        String category,
        String imageUrl,
        int imageWidth,
        int imageHeight
    ) {}

    public record ArtistTranslation(
        String artistId,
        String locale,
        String name,
        String imageAlt,
        String introduction
    ) {}

    public record ArtistLink(
        String artistId,
        int sortOrder,
        String url
    ) {}

    public record ArtistLinkTranslation(
        String artistId,
        int sortOrder,
        String locale,
        String label
    ) {}

    public record ArtistSong(
        String artistId,
        int sortOrder,
        String url
    ) {}

    public record ArtistSongTranslation(
        String artistId,
        int sortOrder,
        String locale,
        String title
    ) {}

    public record Performance(
        String id,
        LocalDate festivalDate,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {}

    public record PerformanceTranslation(
        String performanceId,
        String locale,
        String title,
        String description
    ) {}

    public record PerformanceArtist(
        String performanceId,
        String artistId,
        int displayOrder
    ) {}

    public record TimetableConfig(
        LocalTime axisStartTime,
        LocalTime axisEndTime
    ) {}

    public record ProhibitedItem(
        String id,
        int sortOrder
    ) {}

    public record ProhibitedItemTranslation(
        String itemId,
        String locale,
        String label
    ) {}

    public record ProhibitedMessage(
        String locale,
        String message
    ) {}

    /**
     * Ticket content for one revision. Account and transfer-link fields are
     * intentionally absent, so a manifest that still carries them is rejected
     * as an unknown property instead of silently importing an account.
     */
    public record TicketGuide(
        Integer unitPriceAmount,
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

package dev.espero.festival;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one stored revision back into a manifest.
 *
 * <p>This is deliberately not built on the public {@code CatalogSnapshot}: the
 * snapshot serves Korean public content and drops anything the API does not
 * expose, while an export has to round-trip every revision-scoped row,
 * including other locales, non-current map asset versions and content the
 * public API never returns.</p>
 *
 * <p>The export is lossless. Legacy gaps such as a partially configured ticket
 * schedule are reported rather than guessed, and importing such a manifest
 * fails until an approved value is supplied.</p>
 */
@Service
@Profile("db")
public class CatalogExportService {

    public static final String LEGACY_TICKET_SCHEDULE_UNCONFIGURED = "LEGACY_TICKET_SCHEDULE_UNCONFIGURED";

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogExportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Exports one revision and the findings that block re-importing it. The
     * whole read runs in one repeatable-read transaction so a concurrent
     * publication cannot produce a manifest assembled from two revisions.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportResult export(UUID revisionId) {
        if (revisionId == null) {
            throw new CatalogCliException("A revision id is required.");
        }
        UUID festivalId = festivalId(revisionId);
        CatalogManifest manifest = new CatalogManifest(
            festivalId,
            currentPublishedRevisionId(festivalId),
            festivalDays(revisionId),
            spaces(revisionId),
            spaceTranslations(revisionId),
            spaceSortOrders(revisionId),
            spaceEvents(revisionId),
            spaceMenuItems(revisionId),
            places(revisionId),
            placeTranslations(revisionId),
            maps(revisionId),
            mapTranslations(revisionId),
            mapAssets(revisionId),
            mapAreas(revisionId),
            mapPins(revisionId),
            mapPinTranslations(revisionId),
            mapPinFilterGroupTranslations(revisionId),
            spaceMapTargets(revisionId),
            artists(revisionId),
            artistTranslations(revisionId),
            artistLinks(revisionId),
            artistLinkTranslations(revisionId),
            artistSongs(revisionId),
            artistSongTranslations(revisionId),
            performances(revisionId),
            performanceTranslations(revisionId),
            performanceArtists(revisionId),
            timetableConfig(revisionId),
            prohibitedItems(revisionId),
            prohibitedItemTranslations(revisionId),
            prohibitedMessages(revisionId),
            ticketGuide(revisionId),
            stampGuide(revisionId)
        );
        return new ExportResult(manifest, findings(manifest));
    }

    private List<String> findings(CatalogManifest manifest) {
        List<String> findings = new ArrayList<>();
        CatalogManifest.TicketGuide ticket = manifest.ticketGuide();
        if (ticket != null) {
            boolean anySchedule = ticket.festivalStartDate() != null || ticket.festivalEndDate() != null
                || ticket.dailyTransferOpenTime() != null || ticket.dailyTransferCloseTime() != null
                || ticket.dailyPickupOpenTime() != null || ticket.dailyPickupCloseTime() != null;
            boolean completeSchedule = ticket.festivalStartDate() != null && ticket.festivalEndDate() != null
                && ticket.dailyTransferOpenTime() != null && ticket.dailyTransferCloseTime() != null
                && ticket.dailyPickupOpenTime() != null && ticket.dailyPickupCloseTime() != null;
            if (anySchedule && !completeSchedule) {
                findings.add(LEGACY_TICKET_SCHEDULE_UNCONFIGURED
                    + ": the ticket schedule is partially set. Supply every confirmed date and time,"
                    + " or clear them all, before importing this manifest.");
            }
        }
        return List.copyOf(findings);
    }

    private UUID festivalId(UUID revisionId) {
        List<UUID> ids = query(
            "SELECT festival_id FROM festival_revisions WHERE id = :revisionId",
            revisionId,
            (resultSet, rowNumber) -> resultSet.getObject("festival_id", UUID.class)
        );
        if (ids.size() != 1) {
            throw new CatalogCliException("Revision does not exist: " + revisionId);
        }
        return ids.getFirst();
    }

    private UUID currentPublishedRevisionId(UUID festivalId) {
        return jdbc.query("""
            SELECT id FROM festival_revisions
            WHERE festival_id = :festivalId AND state = 'published'
            """, new MapSqlParameterSource("festivalId", festivalId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)
        ).stream().findFirst().orElse(null);
    }

    private List<CatalogManifest.FestivalDay> festivalDays(UUID revisionId) {
        return query("""
            SELECT festival_date, opens_at, closes_at FROM festival_days
            WHERE festival_revision_id = :revisionId ORDER BY festival_date
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.FestivalDay(
            localDate(resultSet, "festival_date"),
            offsetDateTime(resultSet, "opens_at"),
            offsetDateTime(resultSet, "closes_at")
        ));
    }

    private List<CatalogManifest.Space> spaces(UUID revisionId) {
        return query("""
            SELECT id, category, image_url, image_width, image_height FROM spaces
            WHERE festival_revision_id = :revisionId ORDER BY id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.Space(
            resultSet.getString("id"),
            resultSet.getString("category"),
            resultSet.getString("image_url"),
            resultSet.getInt("image_width"),
            resultSet.getInt("image_height")
        ));
    }

    private List<CatalogManifest.SpaceTranslation> spaceTranslations(UUID revisionId) {
        return query("""
            SELECT space_id, locale, name, image_alt, location_text, operator_text, hours_text,
                   description_text, experience_text, contact_label, contact_url
            FROM space_translations
            WHERE festival_revision_id = :revisionId ORDER BY space_id, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.SpaceTranslation(
            resultSet.getString("space_id"),
            resultSet.getString("locale"),
            resultSet.getString("name"),
            resultSet.getString("image_alt"),
            resultSet.getString("location_text"),
            resultSet.getString("operator_text"),
            resultSet.getString("hours_text"),
            resultSet.getString("description_text"),
            resultSet.getString("experience_text"),
            resultSet.getString("contact_label"),
            resultSet.getString("contact_url")
        ));
    }

    private List<CatalogManifest.SpaceSortOrder> spaceSortOrders(UUID revisionId) {
        return query("""
            SELECT locale, space_id, sort_rank FROM space_sort_orders
            WHERE festival_revision_id = :revisionId ORDER BY locale, sort_rank, space_id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.SpaceSortOrder(
            resultSet.getString("locale"),
            resultSet.getString("space_id"),
            resultSet.getInt("sort_rank")
        ));
    }

    private List<CatalogManifest.SpaceEvent> spaceEvents(UUID revisionId) {
        return query("""
            SELECT space_id, locale, sort_order, content FROM space_events
            WHERE festival_revision_id = :revisionId ORDER BY space_id, locale, sort_order
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.SpaceEvent(
            resultSet.getString("space_id"),
            resultSet.getString("locale"),
            resultSet.getInt("sort_order"),
            resultSet.getString("content")
        ));
    }

    private List<CatalogManifest.SpaceMenuItem> spaceMenuItems(UUID revisionId) {
        return query("""
            SELECT space_id, locale, sort_order, name, price_amount FROM space_menu_items
            WHERE festival_revision_id = :revisionId ORDER BY space_id, locale, sort_order
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.SpaceMenuItem(
            resultSet.getString("space_id"),
            resultSet.getString("locale"),
            resultSet.getInt("sort_order"),
            resultSet.getString("name"),
            resultSet.getInt("price_amount")
        ));
    }

    private List<CatalogManifest.Place> places(UUID revisionId) {
        return query("""
            SELECT id, kind, space_id FROM places
            WHERE festival_revision_id = :revisionId ORDER BY id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.Place(
            resultSet.getString("id"),
            resultSet.getString("kind"),
            resultSet.getString("space_id")
        ));
    }

    private List<CatalogManifest.PlaceTranslation> placeTranslations(UUID revisionId) {
        return query("""
            SELECT place_id, locale, name, location_text, hours_text, description_text, usage_text
            FROM place_translations
            WHERE festival_revision_id = :revisionId ORDER BY place_id, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.PlaceTranslation(
            resultSet.getString("place_id"),
            resultSet.getString("locale"),
            resultSet.getString("name"),
            resultSet.getString("location_text"),
            resultSet.getString("hours_text"),
            resultSet.getString("description_text"),
            resultSet.getString("usage_text")
        ));
    }

    private List<CatalogManifest.MapDefinition> maps(UUID revisionId) {
        return query("""
            SELECT id, kind, sort_rank, current_version FROM maps
            WHERE festival_revision_id = :revisionId ORDER BY sort_rank, id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.MapDefinition(
            resultSet.getString("id"),
            resultSet.getString("kind"),
            resultSet.getInt("sort_rank"),
            resultSet.getString("current_version")
        ));
    }

    private List<CatalogManifest.MapTranslation> mapTranslations(UUID revisionId) {
        return query("""
            SELECT map_id, locale, name FROM map_translations
            WHERE festival_revision_id = :revisionId ORDER BY map_id, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.MapTranslation(
            resultSet.getString("map_id"),
            resultSet.getString("locale"),
            resultSet.getString("name")
        ));
    }

    private List<CatalogManifest.MapAsset> mapAssets(UUID revisionId) {
        return query("""
            SELECT map_id, version, image_url, image_alt, image_width, image_height
            FROM map_asset_versions
            WHERE festival_revision_id = :revisionId ORDER BY map_id, version
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.MapAsset(
            resultSet.getString("map_id"),
            resultSet.getString("version"),
            resultSet.getString("image_url"),
            resultSet.getString("image_alt"),
            resultSet.getInt("image_width"),
            resultSet.getInt("image_height")
        ));
    }

    private List<CatalogManifest.MapArea> mapAreas(UUID revisionId) {
        return query("""
            SELECT id, target_map_id FROM map_areas
            WHERE festival_revision_id = :revisionId ORDER BY id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.MapArea(
            resultSet.getString("id"),
            resultSet.getString("target_map_id")
        ));
    }

    private List<CatalogManifest.MapPin> mapPins(UUID revisionId) {
        return query("""
            SELECT map_id, map_version, id, category, filter_group, x, y, place_id, area_id
            FROM map_pins
            WHERE festival_revision_id = :revisionId ORDER BY map_id, map_version, id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.MapPin(
            resultSet.getString("map_id"),
            resultSet.getString("map_version"),
            resultSet.getString("id"),
            resultSet.getString("category"),
            resultSet.getString("filter_group"),
            coordinate(resultSet, "x"),
            coordinate(resultSet, "y"),
            resultSet.getString("place_id"),
            resultSet.getString("area_id")
        ));
    }

    private List<CatalogManifest.MapPinTranslation> mapPinTranslations(UUID revisionId) {
        return query("""
            SELECT map_id, map_version, pin_id, locale, label FROM map_pin_translations
            WHERE festival_revision_id = :revisionId ORDER BY map_id, map_version, pin_id, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.MapPinTranslation(
            resultSet.getString("map_id"),
            resultSet.getString("map_version"),
            resultSet.getString("pin_id"),
            resultSet.getString("locale"),
            resultSet.getString("label")
        ));
    }

    private List<CatalogManifest.MapPinFilterGroupTranslation> mapPinFilterGroupTranslations(UUID revisionId) {
        return query("""
            SELECT filter_group, locale, label FROM map_pin_filter_group_translations
            WHERE festival_revision_id = :revisionId ORDER BY filter_group, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.MapPinFilterGroupTranslation(
            resultSet.getString("filter_group"),
            resultSet.getString("locale"),
            resultSet.getString("label")
        ));
    }

    private List<CatalogManifest.SpaceMapTarget> spaceMapTargets(UUID revisionId) {
        return query("""
            SELECT space_id, map_id, map_version, pin_id, place_id FROM space_map_targets
            WHERE festival_revision_id = :revisionId ORDER BY space_id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.SpaceMapTarget(
            resultSet.getString("space_id"),
            resultSet.getString("map_id"),
            resultSet.getString("map_version"),
            resultSet.getString("pin_id"),
            resultSet.getString("place_id")
        ));
    }

    private List<CatalogManifest.Artist> artists(UUID revisionId) {
        return query("""
            SELECT id, category, image_url, image_width, image_height FROM artists
            WHERE festival_revision_id = :revisionId ORDER BY id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.Artist(
            resultSet.getString("id"),
            resultSet.getString("category"),
            resultSet.getString("image_url"),
            resultSet.getInt("image_width"),
            resultSet.getInt("image_height")
        ));
    }

    private List<CatalogManifest.ArtistTranslation> artistTranslations(UUID revisionId) {
        return query("""
            SELECT artist_id, locale, name, image_alt, introduction FROM artist_translations
            WHERE festival_revision_id = :revisionId ORDER BY artist_id, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ArtistTranslation(
            resultSet.getString("artist_id"),
            resultSet.getString("locale"),
            resultSet.getString("name"),
            resultSet.getString("image_alt"),
            resultSet.getString("introduction")
        ));
    }

    private List<CatalogManifest.ArtistLink> artistLinks(UUID revisionId) {
        return query("""
            SELECT artist_id, sort_order, url FROM artist_links
            WHERE festival_revision_id = :revisionId ORDER BY artist_id, sort_order
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ArtistLink(
            resultSet.getString("artist_id"),
            resultSet.getInt("sort_order"),
            resultSet.getString("url")
        ));
    }

    private List<CatalogManifest.ArtistLinkTranslation> artistLinkTranslations(UUID revisionId) {
        return query("""
            SELECT artist_id, sort_order, locale, label FROM artist_link_translations
            WHERE festival_revision_id = :revisionId ORDER BY artist_id, sort_order, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ArtistLinkTranslation(
            resultSet.getString("artist_id"),
            resultSet.getInt("sort_order"),
            resultSet.getString("locale"),
            resultSet.getString("label")
        ));
    }

    private List<CatalogManifest.ArtistSong> artistSongs(UUID revisionId) {
        return query("""
            SELECT artist_id, sort_order, url FROM artist_songs
            WHERE festival_revision_id = :revisionId ORDER BY artist_id, sort_order
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ArtistSong(
            resultSet.getString("artist_id"),
            resultSet.getInt("sort_order"),
            resultSet.getString("url")
        ));
    }

    private List<CatalogManifest.ArtistSongTranslation> artistSongTranslations(UUID revisionId) {
        return query("""
            SELECT artist_id, sort_order, locale, title FROM artist_song_translations
            WHERE festival_revision_id = :revisionId ORDER BY artist_id, sort_order, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ArtistSongTranslation(
            resultSet.getString("artist_id"),
            resultSet.getInt("sort_order"),
            resultSet.getString("locale"),
            resultSet.getString("title")
        ));
    }

    private List<CatalogManifest.Performance> performances(UUID revisionId) {
        return query("""
            SELECT id, festival_date, starts_at, ends_at FROM performances
            WHERE festival_revision_id = :revisionId ORDER BY starts_at, id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.Performance(
            resultSet.getString("id"),
            localDate(resultSet, "festival_date"),
            offsetDateTime(resultSet, "starts_at"),
            offsetDateTime(resultSet, "ends_at")
        ));
    }

    private List<CatalogManifest.PerformanceTranslation> performanceTranslations(UUID revisionId) {
        return query("""
            SELECT performance_id, locale, title, description FROM performance_translations
            WHERE festival_revision_id = :revisionId ORDER BY performance_id, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.PerformanceTranslation(
            resultSet.getString("performance_id"),
            resultSet.getString("locale"),
            resultSet.getString("title"),
            resultSet.getString("description")
        ));
    }

    private List<CatalogManifest.PerformanceArtist> performanceArtists(UUID revisionId) {
        return query("""
            SELECT performance_id, artist_id, display_order FROM performance_artists
            WHERE festival_revision_id = :revisionId ORDER BY performance_id, display_order, artist_id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.PerformanceArtist(
            resultSet.getString("performance_id"),
            resultSet.getString("artist_id"),
            resultSet.getInt("display_order")
        ));
    }

    private CatalogManifest.TimetableConfig timetableConfig(UUID revisionId) {
        return query("""
            SELECT axis_start_time, axis_end_time FROM timetable_configs
            WHERE festival_revision_id = :revisionId
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.TimetableConfig(
            localTime(resultSet, "axis_start_time"),
            localTime(resultSet, "axis_end_time")
        )).stream().findFirst().orElse(null);
    }

    private List<CatalogManifest.ProhibitedItem> prohibitedItems(UUID revisionId) {
        return query("""
            SELECT id, sort_order FROM prohibited_items
            WHERE festival_revision_id = :revisionId ORDER BY sort_order, id
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ProhibitedItem(
            resultSet.getString("id"),
            resultSet.getInt("sort_order")
        ));
    }

    private List<CatalogManifest.ProhibitedItemTranslation> prohibitedItemTranslations(UUID revisionId) {
        return query("""
            SELECT item_id, locale, label FROM prohibited_item_translations
            WHERE festival_revision_id = :revisionId ORDER BY item_id, locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ProhibitedItemTranslation(
            resultSet.getString("item_id"),
            resultSet.getString("locale"),
            resultSet.getString("label")
        ));
    }

    private List<CatalogManifest.ProhibitedMessage> prohibitedMessages(UUID revisionId) {
        return query("""
            SELECT locale, message FROM prohibited_messages
            WHERE festival_revision_id = :revisionId ORDER BY locale
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.ProhibitedMessage(
            resultSet.getString("locale"),
            resultSet.getString("message")
        ));
    }

    /** Reads ticket content only. Legacy account columns are never selected. */
    private CatalogManifest.TicketGuide ticketGuide(UUID revisionId) {
        return query("""
            SELECT unit_price_amount, map_id, place_id, pin_id, map_version, instructions,
                   festival_start_date, festival_end_date, daily_transfer_open_time,
                   daily_transfer_close_time, daily_pickup_open_time, daily_pickup_close_time
            FROM ticket_guide_revisions
            WHERE festival_revision_id = :revisionId AND id = 1
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.TicketGuide(
            (Integer) resultSet.getObject("unit_price_amount"),
            resultSet.getString("map_id"),
            resultSet.getString("place_id"),
            resultSet.getString("pin_id"),
            resultSet.getString("map_version"),
            strings(resultSet, "instructions"),
            localDate(resultSet, "festival_start_date"),
            localDate(resultSet, "festival_end_date"),
            localTime(resultSet, "daily_transfer_open_time"),
            localTime(resultSet, "daily_transfer_close_time"),
            localTime(resultSet, "daily_pickup_open_time"),
            localTime(resultSet, "daily_pickup_close_time")
        )).stream().findFirst().orElse(null);
    }

    private CatalogManifest.StampGuide stampGuide(UUID revisionId) {
        return query("""
            SELECT title, dates, instructions, reward_name, reward_location_text,
                   reward_hours_text, reward_notice, qr_value
            FROM stamp_guide_revisions
            WHERE festival_revision_id = :revisionId AND id = 1
            """, revisionId, (resultSet, rowNumber) -> new CatalogManifest.StampGuide(
            resultSet.getString("title"),
            dates(resultSet),
            strings(resultSet, "instructions"),
            resultSet.getString("reward_name"),
            resultSet.getString("reward_location_text"),
            resultSet.getString("reward_hours_text"),
            resultSet.getString("reward_notice"),
            resultSet.getString("qr_value")
        )).stream().findFirst().orElse(null);
    }

    private <T> List<T> query(String sql, UUID revisionId, RowMapper<T> mapper) {
        return jdbc.query(sql, new MapSqlParameterSource("revisionId", revisionId), mapper);
    }

    private LocalDate localDate(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, LocalDate.class);
    }

    private LocalTime localTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, LocalTime.class);
    }

    private OffsetDateTime offsetDateTime(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.withOffsetSameInstant(java.time.ZoneOffset.ofHours(9));
    }

    /** Keeps the stored numeric scale so a round trip does not change a pin. */
    private BigDecimal coordinate(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getBigDecimal(column);
    }

    private List<String> strings(ResultSet resultSet, String column) throws SQLException {
        java.sql.Array array = resultSet.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private List<LocalDate> dates(ResultSet resultSet) throws SQLException {
        java.sql.Array array = resultSet.getArray("dates");
        if (array == null) {
            return List.of();
        }
        List<LocalDate> values = new ArrayList<>();
        for (java.sql.Date date : (java.sql.Date[]) array.getArray()) {
            values.add(date.toLocalDate());
        }
        return List.copyOf(values);
    }

    /** An exported revision plus the findings that block importing it again. */
    public record ExportResult(CatalogManifest manifest, List<String> findings) {

        public ExportResult {
            findings = List.copyOf(findings);
        }
    }
}

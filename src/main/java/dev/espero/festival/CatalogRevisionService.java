package dev.espero.festival;

import dev.espero.festival.persistence.CatalogSnapshotStore;
import dev.espero.festival.persistence.PerformanceRevisionValidator;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Developer-only revision workflow. There is deliberately no HTTP adapter for
 * this service: import, validation, publication and rollback run from the
 * separate non-web CLI entry point.
 */
@Service
@Profile("db")
public class CatalogRevisionService {

    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CatalogSnapshotStore snapshots;
    private final PerformanceRevisionValidator performanceRevisions;
    private final CatalogManifestReader manifests;
    private final Clock clock;

    public CatalogRevisionService(
        NamedParameterJdbcTemplate jdbc,
        DataSource dataSource,
        CatalogSnapshotStore snapshots,
        PerformanceRevisionValidator performanceRevisions,
        CatalogManifestReader manifests,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.snapshots = snapshots;
        this.performanceRevisions = performanceRevisions;
        this.manifests = manifests;
        this.clock = clock;
    }

    /** Reads, validates and atomically inserts a new draft revision. */
    @Transactional
    public UUID importManifest(java.nio.file.Path manifestPath, String actor) {
        CatalogManifestReader.ManifestDocument document = manifests.read(manifestPath);
        CatalogManifest manifest = document.manifest();
        String safeActor = actor(actor);
        UUID festivalId = manifest.festivalId();
        lockFestival(festivalId);

        Instant now = clock.instant();
        UUID revisionId = UUID.randomUUID();
        long revisionNumber = nextRevisionNumber(festivalId);
        insertRevision(revisionId, festivalId, revisionNumber, now);
        insertManifest(revisionId, manifest, now);

        // This is a draft validation. Any exception rolls back every inserted
        // row, including the revision and its audit entry.
        validateStoredRevision(revisionId);
        audit(festivalId, revisionId, "IMPORT", null, safeActor, document.sha256(), now);
        return revisionId;
    }

    /** Validates a draft or archived revision without changing its content. */
    @Transactional
    public void validateRevision(UUID revisionId, String actor) {
        Revision revision = requireRevision(revisionId);
        validateStoredRevision(revisionId);
        audit(
            revision.festivalId(), revisionId, "VALIDATE", null, actor(actor), null, clock.instant()
        );
    }

    /** Revalidates and publishes one complete draft in one database transaction. */
    @Transactional
    public void publish(UUID revisionId, String actor) {
        Revision revision = requireRevision(revisionId);
        lockFestival(revision.festivalId());
        publishLocked(revision, actor(actor), null);
    }

    /**
     * Copies an archived revision into a new revision, preserving map versions
     * and excluding live crowding state, then publishes the copy atomically.
     */
    @Transactional
    public UUID rollback(UUID archivedRevisionId, String actor) {
        Revision source = requireRevision(archivedRevisionId);
        require("archived".equals(source.state()), "Rollback source must be archived.");
        lockFestival(source.festivalId());

        Instant now = clock.instant();
        UUID newRevisionId = UUID.randomUUID();
        long revisionNumber = nextRevisionNumber(source.festivalId());
        insertRevision(newRevisionId, source.festivalId(), revisionNumber, now);
        copyRevision(source.id(), newRevisionId);
        validateStoredRevision(newRevisionId);

        String safeActor = actor(actor);
        audit(source.festivalId(), newRevisionId, "ROLLBACK", source.id(), safeActor, null, now);
        publishLocked(new Revision(newRevisionId, source.festivalId(), revisionNumber, "draft"), safeActor, source.id());
        return newRevisionId;
    }

    private void publishLocked(Revision revision, String actor, UUID sourceRevisionId) {
        require("draft".equals(revision.state()) || "scheduled".equals(revision.state()),
            "Only a draft or scheduled revision can be published.");
        Long publishedRevisionNumber = currentPublishedRevisionNumber(revision.festivalId());
        require(publishedRevisionNumber == null || revision.revisionNumber() > publishedRevisionNumber,
            "A revision older than the current published revision cannot be published.");
        // Re-read after the festival row lock so the validation and pointer
        // swap observe a single serialized operation.
        validateStoredRevision(revision.id());
        Instant now = clock.instant();
        jdbc.update("""
            UPDATE festival_revisions
            SET state = 'archived', updated_at = :now
            WHERE festival_id = :festivalId AND state = 'published'
            """, new MapSqlParameterSource()
            .addValue("festivalId", revision.festivalId())
            .addValue("now", atUtc(now)));
        int updated = jdbc.update("""
            UPDATE festival_revisions
            SET state = 'published',
                approved_at = COALESCE(approved_at, :now),
                published_at = :now,
                updated_at = :now
            WHERE id = :revisionId
              AND festival_id = :festivalId
              AND state IN ('draft', 'scheduled')
            """, new MapSqlParameterSource()
            .addValue("revisionId", revision.id())
            .addValue("festivalId", revision.festivalId())
            .addValue("now", atUtc(now)));
        require(updated == 1, "The revision changed state before publication.");
        audit(revision.festivalId(), revision.id(), "PUBLISH", sourceRevisionId, actor, null, now);
    }

    private void insertManifest(UUID revisionId, CatalogManifest manifest, Instant now) {
        insertFestivalDays(revisionId, manifest.festivalDays(), now);
        batch("""
            INSERT INTO spaces (festival_revision_id, id, category, image_url, image_width, image_height)
            VALUES (:revisionId, :id, :category, :imageUrl, :imageWidth, :imageHeight)
            """, manifest.spaces().stream().map(space -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("id", space.id())
            .addValue("category", space.category())
            .addValue("imageUrl", space.imageUrl())
            .addValue("imageWidth", space.imageWidth())
            .addValue("imageHeight", space.imageHeight())
        ).toList());
        batch("""
            INSERT INTO space_translations (
                festival_revision_id, space_id, locale, name, image_alt, location_text,
                operator_text, hours_text, description_text, experience_text,
                contact_label, contact_url
            ) VALUES (
                :revisionId, :spaceId, :locale, :name, :imageAlt, :locationText,
                :operatorText, :hoursText, :descriptionText, :experienceText,
                :contactLabel, :contactUrl
            )
            """, manifest.spaceTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("spaceId", row.spaceId())
            .addValue("locale", row.locale())
            .addValue("name", row.name())
            .addValue("imageAlt", row.imageAlt())
            .addValue("locationText", row.locationText())
            .addValue("operatorText", row.operatorText())
            .addValue("hoursText", row.hoursText())
            .addValue("descriptionText", row.descriptionText())
            .addValue("experienceText", row.experienceText())
            .addValue("contactLabel", row.contactLabel())
            .addValue("contactUrl", row.contactUrl())
        ).toList());
        batch("""
            INSERT INTO space_sort_orders (festival_revision_id, locale, space_id, sort_rank)
            VALUES (:revisionId, :locale, :spaceId, :sortRank)
            """, manifest.spaceSortOrders().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("locale", row.locale())
            .addValue("spaceId", row.spaceId())
            .addValue("sortRank", row.sortRank())
        ).toList());
        batch("""
            INSERT INTO space_events (festival_revision_id, space_id, locale, sort_order, content)
            VALUES (:revisionId, :spaceId, :locale, :sortOrder, :content)
            """, manifest.spaceEvents().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("spaceId", row.spaceId())
            .addValue("locale", row.locale())
            .addValue("sortOrder", row.sortOrder())
            .addValue("content", row.content())
        ).toList());
        batch("""
            INSERT INTO space_menu_items (
                festival_revision_id, space_id, locale, sort_order, name, price_amount, currency
            ) VALUES (:revisionId, :spaceId, :locale, :sortOrder, :name, :priceAmount, 'KRW')
            """, manifest.spaceMenuItems().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("spaceId", row.spaceId())
            .addValue("locale", row.locale())
            .addValue("sortOrder", row.sortOrder())
            .addValue("name", row.name())
            .addValue("priceAmount", row.priceAmount())
        ).toList());
        batch("""
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            VALUES (:revisionId, :id, :kind, :spaceId)
            """, manifest.places().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("id", row.id())
            .addValue("kind", row.kind())
            .addValue("spaceId", row.spaceId())
        ).toList());
        batch("""
            INSERT INTO place_translations (
                festival_revision_id, place_id, locale, name, location_text,
                hours_text, description_text, usage_text
            ) VALUES (
                :revisionId, :placeId, :locale, :name, :locationText,
                :hoursText, :descriptionText, :usageText
            )
            """, manifest.placeTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("placeId", row.placeId())
            .addValue("locale", row.locale())
            .addValue("name", row.name())
            .addValue("locationText", row.locationText())
            .addValue("hoursText", row.hoursText())
            .addValue("descriptionText", row.descriptionText())
            .addValue("usageText", row.usageText())
        ).toList());
        batch("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES (:revisionId, :id, :kind, :sortRank, :currentVersion)
            """, manifest.maps().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("id", row.id())
            .addValue("kind", row.kind())
            .addValue("sortRank", row.sortRank())
            .addValue("currentVersion", row.currentVersion())
        ).toList());
        batch("""
            INSERT INTO map_translations (festival_revision_id, map_id, locale, name)
            VALUES (:revisionId, :mapId, :locale, :name)
            """, manifest.mapTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("mapId", row.mapId())
            .addValue("locale", row.locale())
            .addValue("name", row.name())
        ).toList());
        batch("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) VALUES (:revisionId, :mapId, :version, :imageUrl, :imageAlt, :imageWidth, :imageHeight)
            """, manifest.mapAssets().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("mapId", row.mapId())
            .addValue("version", row.version())
            .addValue("imageUrl", row.imageUrl())
            .addValue("imageAlt", row.imageAlt())
            .addValue("imageWidth", row.imageWidth())
            .addValue("imageHeight", row.imageHeight())
        ).toList());
        batch("""
            INSERT INTO map_areas (festival_revision_id, id, target_map_id)
            VALUES (:revisionId, :id, :targetMapId)
            """, manifest.mapAreas().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("id", row.id())
            .addValue("targetMapId", row.targetMapId())
        ).toList());
        batch("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, filter_group,
                x, y, place_id, area_id
            ) VALUES (
                :revisionId, :mapId, :mapVersion, :id, :category, :filterGroup,
                :x, :y, :placeId, :areaId
            )
            """, manifest.mapPins().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("mapId", row.mapId())
            .addValue("mapVersion", row.mapVersion())
            .addValue("id", row.id())
            .addValue("category", row.category())
            .addValue("filterGroup", row.filterGroup())
            .addValue("x", row.x())
            .addValue("y", row.y())
            .addValue("placeId", row.placeId())
            .addValue("areaId", row.areaId())
        ).toList());
        batch("""
            INSERT INTO map_pin_translations (
                festival_revision_id, map_id, map_version, pin_id, locale, label
            ) VALUES (:revisionId, :mapId, :mapVersion, :pinId, :locale, :label)
            """, manifest.mapPinTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("mapId", row.mapId())
            .addValue("mapVersion", row.mapVersion())
            .addValue("pinId", row.pinId())
            .addValue("locale", row.locale())
            .addValue("label", row.label())
        ).toList());
        batch("""
            INSERT INTO map_pin_filter_group_translations (
                festival_revision_id, filter_group, locale, label
            ) VALUES (:revisionId, :filterGroup, :locale, :label)
            """, manifest.mapPinFilterGroupTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("filterGroup", row.filterGroup())
            .addValue("locale", row.locale())
            .addValue("label", row.label())
        ).toList());
        batch("""
            INSERT INTO space_map_targets (
                festival_revision_id, space_id, map_id, map_version, pin_id, place_id
            ) VALUES (:revisionId, :spaceId, :mapId, :mapVersion, :pinId, :placeId)
            """, manifest.spaceMapTargets().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("spaceId", row.spaceId())
            .addValue("mapId", row.mapId())
            .addValue("mapVersion", row.mapVersion())
            .addValue("pinId", row.pinId())
            .addValue("placeId", row.placeId())
        ).toList());
        insertTicketGuide(revisionId, manifest.ticketGuide(), now);
        insertStampGuide(revisionId, manifest.stampGuide(), now);
        insertPerformanceCatalog(revisionId, manifest);
    }

    private void insertPerformanceCatalog(UUID revisionId, CatalogManifest manifest) {
        batch("""
            INSERT INTO artists (
                festival_revision_id, id, category, image_url, image_width, image_height
            ) VALUES (:revisionId, :id, :category, :imageUrl, :imageWidth, :imageHeight)
            """, manifest.artists().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("id", row.id())
            .addValue("category", row.category())
            .addValue("imageUrl", row.imageUrl())
            .addValue("imageWidth", row.imageWidth())
            .addValue("imageHeight", row.imageHeight())
        ).toList());
        batch("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt, introduction
            ) VALUES (:revisionId, :artistId, :locale, :name, :imageAlt, :introduction)
            """, manifest.artistTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("artistId", row.artistId())
            .addValue("locale", row.locale())
            .addValue("name", row.name())
            .addValue("imageAlt", row.imageAlt())
            .addValue("introduction", row.introduction())
        ).toList());
        batch("""
            INSERT INTO artist_links (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, :sortOrder, :url)
            """, manifest.artistLinks().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("artistId", row.artistId())
            .addValue("sortOrder", row.sortOrder())
            .addValue("url", row.url())
        ).toList());
        batch("""
            INSERT INTO artist_link_translations (
                festival_revision_id, artist_id, sort_order, locale, label
            ) VALUES (:revisionId, :artistId, :sortOrder, :locale, :label)
            """, manifest.artistLinkTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("artistId", row.artistId())
            .addValue("sortOrder", row.sortOrder())
            .addValue("locale", row.locale())
            .addValue("label", row.label())
        ).toList());
        batch("""
            INSERT INTO artist_songs (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, :sortOrder, :url)
            """, manifest.artistSongs().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("artistId", row.artistId())
            .addValue("sortOrder", row.sortOrder())
            .addValue("url", row.url())
        ).toList());
        batch("""
            INSERT INTO artist_song_translations (
                festival_revision_id, artist_id, sort_order, locale, title
            ) VALUES (:revisionId, :artistId, :sortOrder, :locale, :title)
            """, manifest.artistSongTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("artistId", row.artistId())
            .addValue("sortOrder", row.sortOrder())
            .addValue("locale", row.locale())
            .addValue("title", row.title())
        ).toList());
        batch("""
            INSERT INTO performances (
                festival_revision_id, id, festival_date, starts_at, ends_at
            ) VALUES (:revisionId, :id, :festivalDate, :startsAt, :endsAt)
            """, manifest.performances().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("id", row.id())
            .addValue("festivalDate", row.festivalDate())
            .addValue("startsAt", row.startsAt())
            .addValue("endsAt", row.endsAt())
        ).toList());
        batch("""
            INSERT INTO performance_translations (
                festival_revision_id, performance_id, locale, title, description
            ) VALUES (:revisionId, :performanceId, :locale, :title, :description)
            """, manifest.performanceTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("performanceId", row.performanceId())
            .addValue("locale", row.locale())
            .addValue("title", row.title())
            .addValue("description", row.description())
        ).toList());
        batch("""
            INSERT INTO performance_artists (
                festival_revision_id, performance_id, artist_id, display_order
            ) VALUES (:revisionId, :performanceId, :artistId, :displayOrder)
            """, manifest.performanceArtists().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("performanceId", row.performanceId())
            .addValue("artistId", row.artistId())
            .addValue("displayOrder", row.displayOrder())
        ).toList());
        if (manifest.timetableConfig() != null) {
            batch("""
                INSERT INTO timetable_configs (
                    festival_revision_id, axis_start_time, axis_end_time
                ) VALUES (:revisionId, :axisStartTime, :axisEndTime)
                """, List.of(new MapSqlParameterSource()
                .addValue("revisionId", revisionId)
                .addValue("axisStartTime", manifest.timetableConfig().axisStartTime())
                .addValue("axisEndTime", manifest.timetableConfig().axisEndTime())));
        }
        batch("""
            INSERT INTO prohibited_items (festival_revision_id, id, sort_order)
            VALUES (:revisionId, :id, :sortOrder)
            """, manifest.prohibitedItems().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("id", row.id())
            .addValue("sortOrder", row.sortOrder())
        ).toList());
        batch("""
            INSERT INTO prohibited_item_translations (
                festival_revision_id, item_id, locale, label
            ) VALUES (:revisionId, :itemId, :locale, :label)
            """, manifest.prohibitedItemTranslations().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("itemId", row.itemId())
            .addValue("locale", row.locale())
            .addValue("label", row.label())
        ).toList());
        batch("""
            INSERT INTO prohibited_messages (festival_revision_id, locale, message)
            VALUES (:revisionId, :locale, :message)
            """, manifest.prohibitedMessages().stream().map(row -> new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("locale", row.locale())
            .addValue("message", row.message())
        ).toList());
    }

    private void insertFestivalDays(UUID revisionId, List<CatalogManifest.FestivalDay> days, Instant now) {
        batch("""
            INSERT INTO festival_days (
                id, festival_revision_id, festival_date, opens_at, closes_at, created_at, updated_at
            ) VALUES (:id, :revisionId, :festivalDate, :opensAt, :closesAt, :now, :now)
            """, days.stream().map(day -> new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("revisionId", revisionId)
            .addValue("festivalDate", day.festivalDate())
            .addValue("opensAt", day.opensAt())
            .addValue("closesAt", day.closesAt())
            .addValue("now", atUtc(now))
        ).toList());
    }

    private void insertTicketGuide(UUID revisionId, CatalogManifest.TicketGuide guide, Instant now) {
        batch("""
            INSERT INTO ticket_guide_revisions (
                festival_revision_id, id, unit_price_amount,
                map_id, place_id, pin_id, map_version, instructions,
                festival_start_date, festival_end_date, daily_transfer_open_time,
                daily_transfer_close_time, daily_pickup_open_time, daily_pickup_close_time, updated_at
            ) VALUES (
                :revisionId, 1, :unitPriceAmount,
                :mapId, :placeId, :pinId, :mapVersion, :instructions,
                :festivalStartDate, :festivalEndDate, :dailyTransferOpenTime,
                :dailyTransferCloseTime, :dailyPickupOpenTime, :dailyPickupCloseTime, :updatedAt
            )
            """, List.of(new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("unitPriceAmount", guide.unitPriceAmount())
            .addValue("mapId", guide.mapId())
            .addValue("placeId", guide.placeId())
            .addValue("pinId", guide.pinId())
            .addValue("mapVersion", guide.mapVersion())
            .addValue("instructions", postgresArray("text", guide.instructions().toArray(String[]::new)))
            .addValue("festivalStartDate", guide.festivalStartDate())
            .addValue("festivalEndDate", guide.festivalEndDate())
            .addValue("dailyTransferOpenTime", guide.dailyTransferOpenTime())
            .addValue("dailyTransferCloseTime", guide.dailyTransferCloseTime())
            .addValue("dailyPickupOpenTime", guide.dailyPickupOpenTime())
            .addValue("dailyPickupCloseTime", guide.dailyPickupCloseTime())
            .addValue("updatedAt", atUtc(now))));
    }

    private void insertStampGuide(UUID revisionId, CatalogManifest.StampGuide guide, Instant now) {
        batch("""
            INSERT INTO stamp_guide_revisions (
                festival_revision_id, id, title, dates, instructions, reward_name,
                reward_location_text, reward_hours_text, reward_notice, qr_value, updated_at
            ) VALUES (
                :revisionId, 1, :title, :dates, :instructions, :rewardName,
                :rewardLocationText, :rewardHoursText, :rewardNotice, :qrValue, :updatedAt
            )
            """, List.of(new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("title", guide.title())
            .addValue("dates", postgresArray(
                "date", guide.dates().stream().map(Date::valueOf).toArray(Date[]::new)
            ))
            .addValue("instructions", postgresArray("text", guide.instructions().toArray(String[]::new)))
            .addValue("rewardName", guide.rewardName())
            .addValue("rewardLocationText", guide.rewardLocationText())
            .addValue("rewardHoursText", guide.rewardHoursText())
            .addValue("rewardNotice", guide.rewardNotice())
            .addValue("qrValue", guide.qrValue())
            .addValue("updatedAt", atUtc(now))));
    }

    private void copyRevision(UUID sourceRevisionId, UUID newRevisionId) {
        copyFestivalDays(sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO spaces (festival_revision_id, id, category, image_url, image_width, image_height)
            SELECT :newRevisionId, id, category, image_url, image_width, image_height
            FROM spaces WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO space_translations (
                festival_revision_id, space_id, locale, name, image_alt, location_text,
                operator_text, hours_text, description_text, experience_text, contact_label, contact_url
            ) SELECT :newRevisionId, space_id, locale, name, image_alt, location_text,
                     operator_text, hours_text, description_text, experience_text, contact_label, contact_url
              FROM space_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO space_sort_orders (festival_revision_id, locale, space_id, sort_rank)
            SELECT :newRevisionId, locale, space_id, sort_rank
            FROM space_sort_orders WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO space_events (festival_revision_id, space_id, locale, sort_order, content)
            SELECT :newRevisionId, space_id, locale, sort_order, content
            FROM space_events WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO space_menu_items (
                festival_revision_id, space_id, locale, sort_order, name, price_amount, currency
            ) SELECT :newRevisionId, space_id, locale, sort_order, name, price_amount, currency
              FROM space_menu_items WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO places (festival_revision_id, id, kind, space_id)
            SELECT :newRevisionId, id, kind, space_id
            FROM places WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO place_translations (
                festival_revision_id, place_id, locale, name, location_text, hours_text, description_text, usage_text
            ) SELECT :newRevisionId, place_id, locale, name, location_text, hours_text, description_text, usage_text
              FROM place_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            SELECT :newRevisionId, id, kind, sort_rank, current_version
            FROM maps WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO map_translations (festival_revision_id, map_id, locale, name)
            SELECT :newRevisionId, map_id, locale, name
            FROM map_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO map_asset_versions (
                festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height
            ) SELECT :newRevisionId, map_id, version, image_url, image_alt, image_width, image_height
              FROM map_asset_versions WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO map_areas (festival_revision_id, id, target_map_id)
            SELECT :newRevisionId, id, target_map_id
            FROM map_areas WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO map_pins (
                festival_revision_id, map_id, map_version, id, category, filter_group, x, y, place_id, area_id
            ) SELECT :newRevisionId, map_id, map_version, id, category, filter_group, x, y, place_id, area_id
              FROM map_pins WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO map_pin_translations (
                festival_revision_id, map_id, map_version, pin_id, locale, label
            ) SELECT :newRevisionId, map_id, map_version, pin_id, locale, label
              FROM map_pin_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO map_pin_filter_group_translations (
                festival_revision_id, filter_group, locale, label
            ) SELECT :newRevisionId, filter_group, locale, label
              FROM map_pin_filter_group_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO space_map_targets (
                festival_revision_id, space_id, map_id, map_version, pin_id, place_id
            ) SELECT :newRevisionId, space_id, map_id, map_version, pin_id, place_id
              FROM space_map_targets WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        // A rollback restores catalog content only. Legacy account and
        // transfer-link columns are left out so a restored revision never
        // resurrects an account that the operational settings now own.
        copy("""
            INSERT INTO ticket_guide_revisions (
                festival_revision_id, id, unit_price_amount, map_id, place_id, pin_id,
                map_version, instructions, festival_start_date, festival_end_date,
                daily_transfer_open_time, daily_transfer_close_time, daily_pickup_open_time,
                daily_pickup_close_time, updated_at
            ) SELECT :newRevisionId, id, unit_price_amount, map_id, place_id, pin_id,
                     map_version, instructions, festival_start_date, festival_end_date,
                     daily_transfer_open_time, daily_transfer_close_time, daily_pickup_open_time,
                     daily_pickup_close_time, updated_at
              FROM ticket_guide_revisions WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO stamp_guide_revisions (
                festival_revision_id, id, title, dates, instructions, reward_name,
                reward_location_text, reward_hours_text, reward_notice, qr_value, updated_at
            ) SELECT :newRevisionId, id, title, dates, instructions, reward_name,
                     reward_location_text, reward_hours_text, reward_notice, qr_value, updated_at
              FROM stamp_guide_revisions WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO artists (
                festival_revision_id, id, category, image_url, image_width, image_height
            ) SELECT :newRevisionId, id, category, image_url, image_width, image_height
              FROM artists WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt, introduction
            ) SELECT :newRevisionId, artist_id, locale, name, image_alt, introduction
              FROM artist_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO artist_links (festival_revision_id, artist_id, sort_order, url)
            SELECT :newRevisionId, artist_id, sort_order, url
            FROM artist_links WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO artist_link_translations (
                festival_revision_id, artist_id, sort_order, locale, label
            ) SELECT :newRevisionId, artist_id, sort_order, locale, label
              FROM artist_link_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO artist_songs (festival_revision_id, artist_id, sort_order, url)
            SELECT :newRevisionId, artist_id, sort_order, url
            FROM artist_songs WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO artist_song_translations (
                festival_revision_id, artist_id, sort_order, locale, title
            ) SELECT :newRevisionId, artist_id, sort_order, locale, title
              FROM artist_song_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO performances (
                festival_revision_id, id, festival_date, starts_at, ends_at
            ) SELECT :newRevisionId, id, festival_date, starts_at, ends_at
              FROM performances WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO performance_translations (
                festival_revision_id, performance_id, locale, title, description
            ) SELECT :newRevisionId, performance_id, locale, title, description
              FROM performance_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO performance_artists (
                festival_revision_id, performance_id, artist_id, display_order
            ) SELECT :newRevisionId, performance_id, artist_id, display_order
              FROM performance_artists WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO timetable_configs (
                festival_revision_id, axis_start_time, axis_end_time
            ) SELECT :newRevisionId, axis_start_time, axis_end_time
              FROM timetable_configs WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO prohibited_items (festival_revision_id, id, sort_order)
            SELECT :newRevisionId, id, sort_order
            FROM prohibited_items WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO prohibited_item_translations (
                festival_revision_id, item_id, locale, label
            ) SELECT :newRevisionId, item_id, locale, label
              FROM prohibited_item_translations WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
        copy("""
            INSERT INTO prohibited_messages (festival_revision_id, locale, message)
            SELECT :newRevisionId, locale, message
            FROM prohibited_messages WHERE festival_revision_id = :sourceRevisionId
            """, sourceRevisionId, newRevisionId);
    }

    private void copyFestivalDays(UUID sourceRevisionId, UUID newRevisionId) {
        List<FestivalDayRow> rows = jdbc.query("""
            SELECT festival_date, opens_at, closes_at
            FROM festival_days WHERE festival_revision_id = :sourceRevisionId
            ORDER BY festival_date
            """, new MapSqlParameterSource("sourceRevisionId", sourceRevisionId),
            (resultSet, rowNumber) -> new FestivalDayRow(
                resultSet.getObject("festival_date", java.time.LocalDate.class),
                resultSet.getObject("opens_at", OffsetDateTime.class),
                resultSet.getObject("closes_at", OffsetDateTime.class)
            ));
        Instant now = clock.instant();
        batch("""
            INSERT INTO festival_days (
                id, festival_revision_id, festival_date, opens_at, closes_at, created_at, updated_at
            ) VALUES (:id, :revisionId, :festivalDate, :opensAt, :closesAt, :now, :now)
            """, rows.stream().map(row -> new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("revisionId", newRevisionId)
            .addValue("festivalDate", row.date())
            .addValue("opensAt", row.opensAt())
            .addValue("closesAt", row.closesAt())
            .addValue("now", atUtc(now))
        ).toList());
    }

    private void copy(String sql, UUID sourceRevisionId, UUID newRevisionId) {
        jdbc.update(sql, new MapSqlParameterSource()
            .addValue("sourceRevisionId", sourceRevisionId)
            .addValue("newRevisionId", newRevisionId));
    }

    private void validateStoredRevision(UUID revisionId) {
        snapshots.loadRevision(revisionId);
        performanceRevisions.validate(revisionId);
    }

    private void insertRevision(UUID id, UUID festivalId, long revisionNumber, Instant now) {
        jdbc.update("""
            INSERT INTO festival_revisions (
                id, festival_id, revision_number, state, approved_at, scheduled_at,
                published_at, created_at, updated_at
            ) VALUES (:id, :festivalId, :revisionNumber, 'draft', NULL, NULL, NULL, :now, :now)
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("festivalId", festivalId)
            .addValue("revisionNumber", revisionNumber)
            .addValue("now", atUtc(now)));
    }

    private void audit(
        UUID festivalId,
        UUID revisionId,
        String action,
        UUID sourceRevisionId,
        String actor,
        String hash,
        Instant now
    ) {
        jdbc.update("""
            INSERT INTO catalog_revision_audit (
                festival_id, revision_id, action, source_revision_id, actor,
                manifest_sha256, metadata, created_at
            ) VALUES (
                :festivalId, :revisionId, :action, :sourceRevisionId, :actor,
                :manifestSha256, CAST(:metadata AS JSONB), :createdAt
            )
            """, new MapSqlParameterSource()
            .addValue("festivalId", festivalId)
            .addValue("revisionId", revisionId)
            .addValue("action", action)
            .addValue("sourceRevisionId", sourceRevisionId)
            .addValue("actor", actor)
            .addValue("manifestSha256", hash)
            .addValue("metadata", "{}")
            .addValue("createdAt", atUtc(now)));
    }

    private UUID lockFestival(UUID festivalId) {
        List<UUID> ids = jdbc.query("""
            SELECT id FROM festivals WHERE id = :festivalId FOR UPDATE
            """, new MapSqlParameterSource("festivalId", festivalId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
        require(ids.size() == 1, "Festival does not exist: " + festivalId);
        return ids.getFirst();
    }

    private long nextRevisionNumber(UUID festivalId) {
        Long value = jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision_number), 0) + 1
            FROM festival_revisions WHERE festival_id = :festivalId
            """, new MapSqlParameterSource("festivalId", festivalId), Long.class);
        return value == null ? 1L : value;
    }

    private Long currentPublishedRevisionNumber(UUID festivalId) {
        List<Long> revisionNumbers = jdbc.query("""
            SELECT revision_number
            FROM festival_revisions
            WHERE festival_id = :festivalId AND state = 'published'
            """, new MapSqlParameterSource("festivalId", festivalId),
            (resultSet, rowNumber) -> resultSet.getLong("revision_number"));
        require(revisionNumbers.size() <= 1, "A festival can have only one published revision.");
        return revisionNumbers.isEmpty() ? null : revisionNumbers.getFirst();
    }

    private Revision requireRevision(UUID revisionId) {
        require(revisionId != null, "Revision id is required.");
        List<Revision> revisions = jdbc.query("""
            SELECT id, festival_id, revision_number, state
            FROM festival_revisions WHERE id = :revisionId
            """, new MapSqlParameterSource("revisionId", revisionId), (resultSet, rowNumber) ->
            new Revision(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("festival_id", UUID.class),
                resultSet.getLong("revision_number"),
                resultSet.getString("state")
            )
        );
        require(revisions.size() == 1, "Revision does not exist: " + revisionId);
        return revisions.getFirst();
    }

    private Array postgresArray(String typeName, Object[] values) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            return connection.createArrayOf(typeName, values);
        } catch (SQLException exception) {
            throw new CatalogCliException("Could not bind a PostgreSQL array parameter.", exception);
        }
    }

    private void batch(String sql, List<MapSqlParameterSource> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows.toArray(MapSqlParameterSource[]::new));
        }
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String actor(String actor) {
        String value = actor == null || actor.isBlank() ? "catalog-cli" : actor.strip();
        require(value.length() <= 100, "Actor is too long.");
        return value;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CatalogCliException(message);
        }
    }

    private record Revision(UUID id, UUID festivalId, long revisionNumber, String state) {}

    private record FestivalDayRow(
        java.time.LocalDate date,
        OffsetDateTime opensAt,
        OffsetDateTime closesAt
    ) {}
}

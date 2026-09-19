package dev.espero.festival.persistence;

import dev.espero.festival.media.ProcessedGoodsImage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence boundary for unattached normalized goods media. */
@Repository
@Profile("db")
public class MediaAssetStore {

    private final NamedParameterJdbcTemplate jdbc;

    public MediaAssetStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
        UUID mediaId,
        UUID festivalId,
        String storageKey,
        ProcessedGoodsImage image,
        Instant createdAt
    ) {
        jdbc.update("""
            INSERT INTO media_assets (
                id, festival_id, purpose, storage_key,
                source_sha256, source_size_bytes, source_width, source_height,
                master_width, master_height, normalized_format, content_type,
                created_at, attached_at, detached_at
            ) VALUES (
                :id, :festivalId, 'GOODS_IMAGE', :storageKey,
                :sourceSha256, :sourceSizeBytes, :sourceWidth, :sourceHeight,
                :masterWidth, :masterHeight, 'WEBP', 'image/webp',
                :createdAt, NULL, NULL
            )
            """, new MapSqlParameterSource()
            .addValue("id", mediaId)
            .addValue("festivalId", festivalId)
            .addValue("storageKey", storageKey)
            .addValue("sourceSha256", image.sourceSha256())
            .addValue("sourceSizeBytes", image.sourceSizeBytes())
            .addValue("sourceWidth", image.sourceWidth())
            .addValue("sourceHeight", image.sourceHeight())
            .addValue("masterWidth", image.masterWidth())
            .addValue("masterHeight", image.masterHeight())
            .addValue("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
    }

    public boolean exists(UUID festivalId, UUID mediaId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM media_assets WHERE festival_id = :festivalId AND id = :mediaId",
            Map.of("festivalId", festivalId, "mediaId", mediaId),
            Long.class
        );
        return count != null && count == 1;
    }

    /** Finds only live media attached to a goods row owned by the same festival. */
    public Optional<ServingMediaAsset> findAttachedForServing(UUID festivalId, UUID mediaId) {
        return jdbc.query("""
            SELECT media.id
            FROM media_assets AS media
            JOIN goods_images AS image
              ON image.media_id = media.id
             AND image.festival_id = media.festival_id
            JOIN goods
              ON goods.id = image.goods_id
             AND goods.festival_id = image.festival_id
            WHERE media.id = :mediaId
              AND media.festival_id = :festivalId
              AND media.purpose = 'GOODS_IMAGE'
              AND media.attached_at IS NOT NULL
              AND media.detached_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("festivalId", festivalId)
                .addValue("mediaId", mediaId),
            (resultSet, rowNumber) -> new ServingMediaAsset(resultSet.getObject("id", UUID.class))
        ).stream().findFirst();
    }

    /** Locks attachable goods-media rows in the deterministic order supplied by the caller. */
    public List<UUID> lockAttachableGoodsImages(UUID festivalId, List<UUID> sortedMediaIds) {
        return jdbc.query("""
            SELECT media.id
            FROM media_assets AS media
            WHERE media.festival_id = :festivalId
              AND media.id IN (:mediaIds)
              AND media.purpose = 'GOODS_IMAGE'
              AND media.attached_at IS NULL
              AND media.detached_at IS NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM goods_images AS image
                  WHERE image.media_id = media.id
              )
            ORDER BY media.id
            FOR UPDATE
            """,
            new MapSqlParameterSource()
                .addValue("festivalId", festivalId)
                .addValue("mediaIds", sortedMediaIds),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)
        );
    }

    public int markGoodsImagesAttached(UUID festivalId, List<UUID> mediaIds, Instant attachedAt) {
        return jdbc.update("""
            UPDATE media_assets
            SET attached_at = :attachedAt
            WHERE festival_id = :festivalId
              AND id IN (:mediaIds)
              AND purpose = 'GOODS_IMAGE'
              AND attached_at IS NULL
              AND detached_at IS NULL
            """, new MapSqlParameterSource()
            .addValue("festivalId", festivalId)
            .addValue("mediaIds", mediaIds)
            .addValue("attachedAt", OffsetDateTime.ofInstant(attachedAt, ZoneOffset.UTC)));
    }

    public int markGoodsImagesDetached(UUID festivalId, List<UUID> mediaIds, Instant detachedAt) {
        if (mediaIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
            UPDATE media_assets
            SET detached_at = :detachedAt
            WHERE festival_id = :festivalId
              AND id IN (:mediaIds)
              AND purpose = 'GOODS_IMAGE'
              AND attached_at IS NOT NULL
              AND detached_at IS NULL
            """, new MapSqlParameterSource()
            .addValue("festivalId", festivalId)
            .addValue("mediaIds", mediaIds)
            .addValue("detachedAt", OffsetDateTime.ofInstant(detachedAt, ZoneOffset.UTC)));
    }

    public record ServingMediaAsset(UUID mediaId) {}
}

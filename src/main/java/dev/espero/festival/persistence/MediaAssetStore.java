package dev.espero.festival.persistence;

import dev.espero.festival.media.ProcessedGoodsImage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
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
}

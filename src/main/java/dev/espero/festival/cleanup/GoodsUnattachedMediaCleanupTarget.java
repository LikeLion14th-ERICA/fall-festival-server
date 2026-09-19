package dev.espero.festival.cleanup;

import dev.espero.festival.media.MediaStorage;
import java.time.Duration;

/** Removes goods image uploads that were never attached after their 24-hour grace period. */
public class GoodsUnattachedMediaCleanupTarget extends GoodsMediaCleanupTargetSupport {

    private static final String TARGET_NAME = "goods_unattached_media";
    private static final String COUNT_SQL = """
        SELECT count(*)
        FROM media_assets AS media
        WHERE media.purpose = 'GOODS_IMAGE'
          AND media.attached_at IS NULL
          AND media.detached_at IS NULL
          AND media.created_at < :cutoff
          AND NOT EXISTS (SELECT 1 FROM goods_images WHERE media_id = media.id)
        """;
    private static final String DELETE_BATCH_SQL = """
        WITH candidates AS (
            SELECT media.id
            FROM media_assets AS media
            WHERE media.purpose = 'GOODS_IMAGE'
              AND media.attached_at IS NULL
              AND media.detached_at IS NULL
              AND media.created_at < :cutoff
              AND NOT EXISTS (SELECT 1 FROM goods_images WHERE media_id = media.id)
            ORDER BY media.created_at ASC, media.id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        DELETE FROM media_assets AS media
        USING candidates
        WHERE media.id = candidates.id
          AND media.purpose = 'GOODS_IMAGE'
          AND media.attached_at IS NULL
          AND media.detached_at IS NULL
          AND media.created_at < :cutoff
          AND NOT EXISTS (SELECT 1 FROM goods_images WHERE media_id = media.id)
        RETURNING media.festival_id, media.id
        """;

    public GoodsUnattachedMediaCleanupTarget(MediaStorage mediaStorage) {
        super(TARGET_NAME, Duration.ofHours(24), COUNT_SQL, DELETE_BATCH_SQL, mediaStorage);
    }
}

package dev.espero.festival.cleanup;

import dev.espero.festival.media.MediaStorage;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared transaction and post-commit behavior for one bounded goods media cleanup target. */
abstract class GoodsMediaCleanupTargetSupport implements CleanupTarget {

    private final String targetName;
    private final Duration retention;
    private final String countSql;
    private final String deleteBatchSql;
    private final MediaStorage mediaStorage;

    GoodsMediaCleanupTargetSupport(
        String targetName,
        Duration retention,
        String countSql,
        String deleteBatchSql,
        MediaStorage mediaStorage
    ) {
        this.targetName = targetName;
        this.retention = retention;
        this.countSql = countSql;
        this.deleteBatchSql = deleteBatchSql;
        this.mediaStorage = mediaStorage;
    }

    @Override
    public final String name() {
        return targetName;
    }

    @Override
    public final CleanupTargetResult run(CleanupTargetContext context) {
        Instant cutoff = context.now().minus(retention);
        Map<String, Object> parameters = Map.of(
            "cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC),
            "batchSize", context.batchSize()
        );
        Long count = context.jdbc().queryForObject(countSql, parameters, Long.class);
        long eligibleCount = count == null ? 0 : count;
        if (context.dryRun()) {
            return new CleanupTargetResult(
                targetName,
                eligibleCount,
                0,
                batchesFor(eligibleCount, context.batchSize()),
                true,
                cutoff
            );
        }

        List<DeletedMedia> deleted = context.jdbc().query(
            deleteBatchSql,
            parameters,
            (resultSet, rowNumber) -> new DeletedMedia(
                resultSet.getObject("festival_id", UUID.class),
                resultSet.getObject("id", UUID.class)
            )
        );
        deleted.forEach(media -> context.afterCommit(deleteFromStorage(media)));
        return new CleanupTargetResult(
            targetName,
            eligibleCount,
            deleted.size(),
            deleted.isEmpty() ? 0 : 1,
            false,
            cutoff
        );
    }

    private CleanupPostCommitAction deleteFromStorage(DeletedMedia media) {
        return new CleanupPostCommitAction() {
            @Override
            public String target() {
                return targetName;
            }

            @Override
            public void run() throws Exception {
                mediaStorage.delete(media.festivalId(), media.mediaId());
            }
        };
    }

    private int batchesFor(long count, int batchSize) {
        return count == 0 ? 0 : (int) ((count + batchSize - 1) / batchSize);
    }

    private record DeletedMedia(UUID festivalId, UUID mediaId) {}
}

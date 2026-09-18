package dev.espero.festival.cleanup;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Removes only completed idempotency responses after their 24-hour replay window. */
@Component
@Profile("db")
public class AdminIdempotencyCleanupTarget implements CleanupTarget {

    private static final String TARGET_NAME = "admin_idempotency_records";
    private static final Duration RETENTION = Duration.ofHours(24);

    private static final String COUNT_SQL = """
        SELECT count(*)
        FROM admin_idempotency_records
        WHERE state = 'COMPLETED' AND completed_at < :cutoff
        """;

    private static final String DELETE_BATCH_SQL = """
        WITH candidates AS (
            SELECT id
            FROM admin_idempotency_records
            WHERE state = 'COMPLETED' AND completed_at < :cutoff
            ORDER BY completed_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        DELETE FROM admin_idempotency_records AS record
        USING candidates
        WHERE record.id = candidates.id
        """;

    @Override
    public String name() {
        return TARGET_NAME;
    }

    @Override
    public CleanupTargetResult run(CleanupTargetContext context) {
        Instant cutoff = context.now().minus(RETENTION);
        long eligibleCount = countEligible(context, cutoff);
        if (context.dryRun()) {
            return new CleanupTargetResult(
                TARGET_NAME,
                eligibleCount,
                0,
                batchesFor(eligibleCount, context.batchSize()),
                true,
                cutoff
            );
        }

        int deletedCount = context.jdbc().update(
            DELETE_BATCH_SQL,
            Map.of("cutoff", atUtc(cutoff), "batchSize", context.batchSize())
        );
        return new CleanupTargetResult(
            TARGET_NAME,
            eligibleCount,
            deletedCount,
            deletedCount == 0 ? 0 : 1,
            false,
            cutoff
        );
    }

    private long countEligible(CleanupTargetContext context, Instant cutoff) {
        Long count = context.jdbc().queryForObject(
            COUNT_SQL,
            Map.of("cutoff", atUtc(cutoff)),
            Long.class
        );
        return count == null ? 0 : count;
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private int batchesFor(long count, int batchSize) {
        return count == 0 ? 0 : (int) ((count + batchSize - 1) / batchSize);
    }
}

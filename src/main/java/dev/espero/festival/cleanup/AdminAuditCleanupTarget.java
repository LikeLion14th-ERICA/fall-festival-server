package dev.espero.festival.cleanup;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deletes administrator audit events older than the approved one-year retention period. */
@Component
@Profile("db")
public class AdminAuditCleanupTarget implements CleanupTarget {

    private static final String TARGET_NAME = "admin_audit_events";
    private static final Period RETENTION = Period.ofYears(1);

    private static final String COUNT_SQL = """
        SELECT count(*)
        FROM admin_audit_events
        WHERE occurred_at < :cutoff
        """;

    private static final String DELETE_BATCH_SQL = """
        WITH candidates AS (
            SELECT id
            FROM admin_audit_events
            WHERE occurred_at < :cutoff
            ORDER BY occurred_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        DELETE FROM admin_audit_events AS audit
        USING candidates
        WHERE audit.id = candidates.id
        """;

    @Override
    public String name() {
        return TARGET_NAME;
    }

    @Override
    public CleanupTargetResult run(CleanupTargetContext context) {
        Instant cutoff = ZonedDateTime.ofInstant(context.now(), ZoneOffset.UTC)
            .minus(RETENTION)
            .toInstant();
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
            parameters(cutoff, context.batchSize())
        );
        int batches = deletedCount == 0 ? 0 : 1;

        return new CleanupTargetResult(
            TARGET_NAME,
            eligibleCount,
            deletedCount,
            batches,
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

    private Map<String, Object> parameters(Instant cutoff, int batchSize) {
        return Map.of("cutoff", atUtc(cutoff), "batchSize", batchSize);
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private int batchesFor(long count, int batchSize) {
        return count == 0 ? 0 : (int) ((count + batchSize - 1) / batchSize);
    }
}

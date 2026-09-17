package dev.espero.festival.cleanup;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Retains account history for one year while preserving each setting's latest
 * state event and durable version watermark.
 */
@Component
@Profile("db")
public class OperationalAccountHistoryCleanupTarget implements CleanupTarget {

    private static final String TARGET_NAME = "operational_account_setting_history";
    private static final Period RETENTION = Period.ofYears(1);

    /*
     * The newest event preserves the version watermark after a privileged direct
     * DELETE. The newest event with an after state preserves the last restorable
     * current value. Neither selection reads a sensitive account field.
     */
    private static final String PROTECTED_HISTORY_SQL = """
        WITH protected_history AS (
            (SELECT DISTINCT ON (festival_id, purpose) id
             FROM operational_account_setting_history
             ORDER BY festival_id, purpose, version DESC, id DESC)
            UNION
            (SELECT DISTINCT ON (festival_id, purpose) id
             FROM operational_account_setting_history
             WHERE after_state IS NOT NULL
             ORDER BY festival_id, purpose, version DESC, id DESC)
        )
        """;

    private static final String COUNT_SQL = PROTECTED_HISTORY_SQL + """
        SELECT count(*)
        FROM operational_account_setting_history AS history
        WHERE history.occurred_at < :cutoff
          AND NOT EXISTS (SELECT 1 FROM protected_history WHERE id = history.id)
        """;

    private static final String DELETE_BATCH_SQL = PROTECTED_HISTORY_SQL + """
        , candidates AS (
            SELECT history.id
            FROM operational_account_setting_history AS history
            WHERE history.occurred_at < :cutoff
              AND NOT EXISTS (SELECT 1 FROM protected_history WHERE id = history.id)
            ORDER BY history.occurred_at ASC, history.id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        DELETE FROM operational_account_setting_history AS history
        USING candidates
        WHERE history.id = candidates.id
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

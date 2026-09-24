package dev.espero.festival.cleanup;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deletes participant trees, including every ciphertext and token hash, seven days after close. */
@Component
@Profile("db")
public class LoveLetterCleanupTarget implements CleanupTarget {
    private static final String NAME = "love_letter_personal_data";
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final String ELIGIBLE = """
        FROM love_letter_participants p
        JOIN love_letter_settings s ON s.festival_id=p.festival_id
        WHERE s.closes_at <= :cutoff
        """;

    @Override public String name() { return NAME; }

    @Override public CleanupTargetResult run(CleanupTargetContext context) {
        Instant cutoff = context.now().minus(RETENTION);
        Map<String, Object> params = Map.of("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC),
            "batchSize", context.batchSize());
        Long eligible = context.jdbc().queryForObject("SELECT count(*) " + ELIGIBLE, params, Long.class);
        long count = eligible == null ? 0 : eligible;
        if (context.dryRun())
            return new CleanupTargetResult(NAME, count, 0, count == 0 ? 0 : (int) ((count + context.batchSize() - 1) / context.batchSize()), true, cutoff);
        int deleted = context.jdbc().update("""
            WITH candidates AS (
                SELECT p.id
                FROM love_letter_participants p
                JOIN love_letter_settings s ON s.festival_id=p.festival_id
                WHERE s.closes_at <= :cutoff
                ORDER BY p.created_at,p.id
                LIMIT :batchSize
                FOR UPDATE OF p SKIP LOCKED
            )
            DELETE FROM love_letter_participants p USING candidates WHERE p.id=candidates.id
            """, params);
        return new CleanupTargetResult(NAME, count, deleted, deleted == 0 ? 0 : 1, false, cutoff);
    }
}

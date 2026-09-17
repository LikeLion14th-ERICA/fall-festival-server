package dev.espero.festival.cleanup;

import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Shared, bounded inputs supplied to every registered cleanup target. */
public record CleanupTargetContext(
    NamedParameterJdbcTemplate jdbc,
    Instant now,
    int batchSize,
    boolean dryRun
) {

    public static final int MAX_BATCH_SIZE = 500;

    public CleanupTargetContext {
        Objects.requireNonNull(jdbc, "Cleanup JDBC template is required");
        Objects.requireNonNull(now, "Cleanup clock time is required");
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Cleanup batch size must be between 1 and 500");
        }
    }
}

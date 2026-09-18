package dev.espero.festival.cleanup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Shared, bounded inputs supplied to every registered cleanup target. */
public record CleanupTargetContext(
    NamedParameterJdbcTemplate jdbc,
    Instant now,
    int batchSize,
    boolean dryRun,
    List<CleanupPostCommitAction> postCommitActions
) {

    public static final int MAX_BATCH_SIZE = 500;

    public CleanupTargetContext {
        Objects.requireNonNull(jdbc, "Cleanup JDBC template is required");
        Objects.requireNonNull(now, "Cleanup clock time is required");
        Objects.requireNonNull(postCommitActions, "Post-commit action list is required");
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Cleanup batch size must be between 1 and 500");
        }
    }

    public CleanupTargetContext(NamedParameterJdbcTemplate jdbc, Instant now, int batchSize, boolean dryRun) {
        this(jdbc, now, batchSize, dryRun, new ArrayList<>());
    }

    /**
     * Schedules work to run after this cleanup transaction commits, such as
     * deleting files for the rows a target just removed. Nothing is scheduled
     * in a dry run, because a dry run must not change anything.
     */
    public void afterCommit(CleanupPostCommitAction action) {
        Objects.requireNonNull(action, "Post-commit action is required");
        if (!dryRun) {
            postCommitActions.add(action);
        }
    }
}

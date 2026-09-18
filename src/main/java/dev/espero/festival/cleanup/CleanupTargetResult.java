package dev.espero.festival.cleanup;

import java.time.Instant;
import java.util.Objects;

/** Structured per-target outcome suitable for logs and metrics. */
public record CleanupTargetResult(
    String target,
    long eligibleCount,
    long deletedCount,
    int batches,
    boolean dryRun,
    Instant cutoff
) {

    public CleanupTargetResult {
        Objects.requireNonNull(target, "Cleanup target name is required");
        Objects.requireNonNull(cutoff, "Cleanup cutoff is required");
        if (target.isBlank()) {
            throw new IllegalArgumentException("Cleanup target name cannot be blank");
        }
        if (eligibleCount < 0 || deletedCount < 0 || batches < 0) {
            throw new IllegalArgumentException("Cleanup counts cannot be negative");
        }
        if (dryRun && deletedCount != 0) {
            throw new IllegalArgumentException("A dry run cannot delete rows");
        }
        if (!dryRun && deletedCount > eligibleCount) {
            throw new IllegalArgumentException("Deleted rows cannot exceed eligible rows");
        }
    }

    public long wouldDeleteCount() {
        return dryRun ? eligibleCount : deletedCount;
    }
}

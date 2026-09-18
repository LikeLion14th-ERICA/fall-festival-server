package dev.espero.festival.cleanup;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Structured cleanup-run outcome. */
public record CleanupRunResult(
    CleanupRunStatus status,
    CleanupMode mode,
    Instant startedAt,
    Instant finishedAt,
    List<CleanupTargetResult> targets
) {

    public CleanupRunResult {
        Objects.requireNonNull(status, "Cleanup run status is required");
        Objects.requireNonNull(mode, "Cleanup run mode is required");
        Objects.requireNonNull(startedAt, "Cleanup start time is required");
        Objects.requireNonNull(finishedAt, "Cleanup finish time is required");
        targets = List.copyOf(Objects.requireNonNull(targets, "Cleanup target results are required"));
    }

    public long eligibleCount() {
        return targets.stream().mapToLong(CleanupTargetResult::eligibleCount).sum();
    }

    public long deletedCount() {
        return targets.stream().mapToLong(CleanupTargetResult::deletedCount).sum();
    }

    public long wouldDeleteCount() {
        return targets.stream().mapToLong(CleanupTargetResult::wouldDeleteCount).sum();
    }
}

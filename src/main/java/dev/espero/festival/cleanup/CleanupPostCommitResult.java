package dev.espero.festival.cleanup;

import java.util.Objects;

/** Outcome of one post-commit action after its bounded retries. */
public record CleanupPostCommitResult(String target, int attempts, boolean succeeded) {

    public CleanupPostCommitResult {
        Objects.requireNonNull(target, "Cleanup target name is required");
        if (attempts < 1) {
            throw new IllegalArgumentException("A post-commit action runs at least once");
        }
    }
}

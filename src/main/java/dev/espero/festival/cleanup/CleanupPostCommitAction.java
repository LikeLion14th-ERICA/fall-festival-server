package dev.espero.festival.cleanup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Work that may run only after the cleanup transaction has committed, such as
 * deleting a stored file whose database row was just removed.
 *
 * <p>The row is already gone when this runs, so an action must be idempotent:
 * running it again after a partial failure, or after another process already
 * finished the work, must succeed without side effects. The job retries a
 * failed action a bounded number of times and reports what still failed.</p>
 */
public interface CleanupPostCommitAction {

    /** The owning cleanup target, used only for results and logs. */
    String target();

    /** Performs the side effect. Throwing marks this attempt as failed. */
    void run() throws Exception;

    /**
     * Deletes one file after commit. A missing file counts as already deleted,
     * which keeps retries and reruns idempotent. The path is never logged.
     */
    static CleanupPostCommitAction deleteFile(String target, Path path) {
        Objects.requireNonNull(target, "Cleanup target name is required");
        Objects.requireNonNull(path, "File path is required");
        return new CleanupPostCommitAction() {
            @Override
            public String target() {
                return target;
            }

            @Override
            public void run() throws Exception {
                Files.deleteIfExists(path);
            }
        };
    }
}

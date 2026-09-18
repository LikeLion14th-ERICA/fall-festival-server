package dev.espero.festival.cleanup;

public enum CleanupRunStatus {
    COMPLETED,
    SKIPPED_DISABLED,
    SKIPPED_UNSAFE_CONFIGURATION,
    SKIPPED_LOCK_NOT_ACQUIRED
}

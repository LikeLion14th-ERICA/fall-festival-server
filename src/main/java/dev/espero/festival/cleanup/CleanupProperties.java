package dev.espero.festival.cleanup;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime controls for the database cleanup job.
 *
 * <p>Cleanup is intentionally opt-in. A dry run is the safe default, and a
 * delete run also requires a separately configured datasource and role.</p>
 */
@ConfigurationProperties(prefix = "festival.cleanup")
public class CleanupProperties {

    private boolean scheduleEnabled;
    private boolean dryRun = true;
    private long scheduleIntervalMs = 86_400_000L;
    private long scheduleInitialDelayMs;
    private int batchSize = CleanupTargetContext.MAX_BATCH_SIZE;
    private long advisoryLockKey = 2_026_091_800_001L;
    private CleanupDataSourceProperties datasource = new CleanupDataSourceProperties();

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public void setScheduleEnabled(boolean scheduleEnabled) {
        this.scheduleEnabled = scheduleEnabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public long getScheduleIntervalMs() {
        return scheduleIntervalMs;
    }

    public void setScheduleIntervalMs(long scheduleIntervalMs) {
        if (scheduleIntervalMs <= 0) {
            throw new IllegalArgumentException("Cleanup schedule interval must be positive");
        }
        this.scheduleIntervalMs = scheduleIntervalMs;
    }

    public long getScheduleInitialDelayMs() {
        return scheduleInitialDelayMs;
    }

    public void setScheduleInitialDelayMs(long scheduleInitialDelayMs) {
        if (scheduleInitialDelayMs < 0) {
            throw new IllegalArgumentException("Cleanup schedule initial delay cannot be negative");
        }
        this.scheduleInitialDelayMs = scheduleInitialDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize <= 0 || batchSize > CleanupTargetContext.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                "Cleanup batch size must be between 1 and " + CleanupTargetContext.MAX_BATCH_SIZE
            );
        }
        this.batchSize = batchSize;
    }

    public long getAdvisoryLockKey() {
        return advisoryLockKey;
    }

    public void setAdvisoryLockKey(long advisoryLockKey) {
        this.advisoryLockKey = advisoryLockKey;
    }

    public CleanupDataSourceProperties getDatasource() {
        return datasource;
    }

    public void setDatasource(CleanupDataSourceProperties datasource) {
        this.datasource = Objects.requireNonNull(datasource, "Cleanup datasource properties are required");
    }

    public boolean hasDedicatedDataSourceAndRole() {
        return datasource != null && datasource.isComplete();
    }

    public static class CleanupDataSourceProperties {

        private String url;
        private String username;
        private String password;
        private String role;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        private boolean isComplete() {
            return hasText(url) && hasText(username) && hasText(password) && hasText(role);
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}

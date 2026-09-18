package dev.espero.festival.cleanup;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Optional scheduler; the configuration condition keeps unsafe delete schedules absent. */
@Component
@Profile("db")
@Conditional(CleanupSchedulingCondition.class)
public class CleanupScheduler {

    private final CleanupJob job;

    public CleanupScheduler(CleanupJob job) {
        this.job = job;
    }

    @Scheduled(
        fixedDelayString = "${festival.cleanup.schedule-interval-ms:86400000}",
        initialDelayString = "${festival.cleanup.schedule-initial-delay-ms:0}"
    )
    public void run() {
        job.runScheduled();
    }
}

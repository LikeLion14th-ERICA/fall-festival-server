package dev.espero.festival.cleanup;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates registered cleanup targets under one PostgreSQL transaction lock. */
@Service
@Profile("db")
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    private final CleanupProperties properties;
    private final CleanupDataSourceProvider dataSourceProvider;
    private final List<CleanupTarget> targets;
    private final Clock clock;

    public CleanupJob(
        CleanupProperties properties,
        CleanupDataSourceProvider dataSourceProvider,
        List<CleanupTarget> targets,
        Clock clock
    ) {
        this.properties = properties;
        this.dataSourceProvider = dataSourceProvider;
        this.targets = targets.stream()
            .sorted(Comparator.comparing(CleanupTarget::name))
            .toList();
        this.clock = clock;
    }

    /** Runs one explicitly selected mode. Delete mode is gated by the dedicated pool. */
    public CleanupRunResult run(CleanupMode mode) {
        Objects.requireNonNull(mode, "Cleanup mode is required");
        Instant startedAt = clock.instant();
        if (mode == CleanupMode.DELETE && !dataSourceProvider.hasDedicatedDataSourceAndRole()) {
            CleanupRunResult result = skipped(
                CleanupRunStatus.SKIPPED_UNSAFE_CONFIGURATION, mode, startedAt
            );
            logResult(result);
            return result;
        }

        DataSource dataSource = dataSourceProvider.dataSource();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(mode == CleanupMode.DRY_RUN);

        try {
            CleanupRunResult result = transaction.execute(status -> runInTransaction(jdbc, mode, startedAt));
            CleanupRunResult nonNullResult = Objects.requireNonNull(result, "Cleanup transaction returned no result");
            logResult(nonNullResult);
            return nonNullResult;
        } catch (RuntimeException exception) {
            log.error("cleanup_run_failed mode={}", mode, exception);
            throw exception;
        }
    }

    /** Entry point used by the optional scheduler. */
    public CleanupRunResult runScheduled() {
        CleanupMode mode = properties.isDryRun() ? CleanupMode.DRY_RUN : CleanupMode.DELETE;
        if (!properties.isScheduleEnabled()) {
            CleanupRunResult result = skipped(
                CleanupRunStatus.SKIPPED_DISABLED, mode, clock.instant()
            );
            logResult(result);
            return result;
        }
        return run(mode);
    }

    private CleanupRunResult runInTransaction(
        NamedParameterJdbcTemplate jdbc,
        CleanupMode mode,
        Instant startedAt
    ) {
        Boolean lockAcquired = jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(:lockKey)",
            java.util.Map.of("lockKey", properties.getAdvisoryLockKey()),
            Boolean.class
        );
        if (!Boolean.TRUE.equals(lockAcquired)) {
            return skipped(CleanupRunStatus.SKIPPED_LOCK_NOT_ACQUIRED, mode, startedAt);
        }

        Instant now = clock.instant();
        CleanupTargetContext context = new CleanupTargetContext(
            jdbc,
            now,
            properties.getBatchSize(),
            mode == CleanupMode.DRY_RUN
        );
        List<CleanupTargetResult> targetResults = targets.stream()
            .map(target -> target.run(context))
            .toList();
        return new CleanupRunResult(
            CleanupRunStatus.COMPLETED,
            mode,
            startedAt,
            clock.instant(),
            targetResults
        );
    }

    private CleanupRunResult skipped(CleanupRunStatus status, CleanupMode mode, Instant startedAt) {
        return new CleanupRunResult(status, mode, startedAt, clock.instant(), List.of());
    }

    private void logResult(CleanupRunResult result) {
        long durationMs = Math.max(
            0,
            result.finishedAt().toEpochMilli() - result.startedAt().toEpochMilli()
        );
        log.info(
            "cleanup_run status={} mode={} target_count={} eligible_count={} deleted_count={} duration_ms={}",
            result.status(),
            result.mode(),
            result.targets().size(),
            result.eligibleCount(),
            result.deletedCount(),
            durationMs
        );
        result.targets().forEach(target -> log.info(
            "cleanup_target target={} dry_run={} eligible_count={} deleted_count={} batch_count={}",
            target.target(),
            target.dryRun(),
            target.eligibleCount(),
            target.deletedCount(),
            target.batches()
        ));
    }
}

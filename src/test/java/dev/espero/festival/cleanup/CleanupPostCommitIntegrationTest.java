package dev.espero.festival.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the file-owner extension point: work scheduled by a target runs
 * only after the cleanup transaction commits and is retried a bounded number
 * of times.
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class CleanupPostCommitIntegrationTest {

    private static final String TARGET = "stored_file_rows";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("festival.cleanup.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("festival.cleanup.datasource.username", POSTGRES::getUsername);
        registry.add("festival.cleanup.datasource.password", POSTGRES::getPassword);
        registry.add("festival.cleanup.datasource.role", POSTGRES::getUsername);
    }

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private CleanupProperties properties;

    @Autowired
    private CleanupDataSourceProvider dataSourceProvider;

    @TempDir
    private Path tempDir;

    private long originalRetryDelayMs;
    private int originalMaxAttempts;

    @BeforeEach
    void setUp() {
        originalRetryDelayMs = properties.getPostCommitRetryDelayMs();
        originalMaxAttempts = properties.getPostCommitMaxAttempts();
        properties.setPostCommitRetryDelayMs(0);
        properties.setPostCommitMaxAttempts(3);
        properties.getDatasource().setRole(POSTGRES.getUsername());
        jdbc.getJdbcTemplate().execute("CREATE TABLE IF NOT EXISTS stored_file_rows (id INTEGER PRIMARY KEY)");
        jdbc.update("DELETE FROM stored_file_rows", Map.of());
        jdbc.update("INSERT INTO stored_file_rows (id) VALUES (1), (2)", Map.of());
    }

    @AfterEach
    void restoreProperties() {
        properties.setPostCommitRetryDelayMs(originalRetryDelayMs);
        properties.setPostCommitMaxAttempts(originalMaxAttempts);
    }

    @Test
    void runsFileActionsOnlyAfterCommitAndRetriesATransientFailure() throws Exception {
        Path first = Files.writeString(tempDir.resolve("first.bin"), "a");
        Path second = Files.writeString(tempDir.resolve("second.bin"), "b");
        AtomicInteger flakyAttempts = new AtomicInteger();
        AtomicLong rowsSeenAfterCommit = new AtomicLong(-1);

        CleanupRunResult result = job(context -> {
            long deleted = context.jdbc().update("DELETE FROM stored_file_rows", Map.of());
            context.afterCommit(CleanupPostCommitAction.deleteFile(TARGET, first));
            context.afterCommit(new CleanupPostCommitAction() {
                @Override
                public String target() {
                    return TARGET;
                }

                @Override
                public void run() throws Exception {
                    // A separate connection only sees the deletion once it has committed.
                    rowsSeenAfterCommit.set(rowCount());
                    if (flakyAttempts.incrementAndGet() < 3) {
                        throw new java.io.IOException("transient storage failure");
                    }
                    Files.deleteIfExists(second);
                }
            });
            return result(context, deleted);
        }).run(CleanupMode.DELETE);

        assertThat(result.status()).isEqualTo(CleanupRunStatus.COMPLETED);
        assertThat(rowCount()).isZero();
        assertThat(rowsSeenAfterCommit.get()).isZero();
        assertThat(first).doesNotExist();
        assertThat(second).doesNotExist();
        assertThat(result.postCommit()).containsExactly(
            new CleanupPostCommitResult(TARGET, 1, true),
            new CleanupPostCommitResult(TARGET, 3, true)
        );
        assertThat(result.postCommitFailedCount()).isZero();
    }

    @Test
    void reportsAnActionThatStillFailsAfterBoundedRetriesWithoutUndoingTheCommit() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        CleanupRunResult result = job(context -> {
            long deleted = context.jdbc().update("DELETE FROM stored_file_rows", Map.of());
            context.afterCommit(new CleanupPostCommitAction() {
                @Override
                public String target() {
                    return TARGET;
                }

                @Override
                public void run() throws Exception {
                    attempts.incrementAndGet();
                    throw new java.io.IOException("storage unavailable");
                }
            });
            return result(context, deleted);
        }).run(CleanupMode.DELETE);

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(result.postCommit()).containsExactly(new CleanupPostCommitResult(TARGET, 3, false));
        assertThat(result.postCommitFailedCount()).isEqualTo(1);
        assertThat(rowCount()).isZero();
    }

    @Test
    void doesNotRunScheduledActionsWhenTheTransactionRollsBack() throws Exception {
        Path file = Files.writeString(tempDir.resolve("kept.bin"), "c");

        CleanupJob job = job(context -> {
            context.jdbc().update("DELETE FROM stored_file_rows", Map.of());
            context.afterCommit(CleanupPostCommitAction.deleteFile(TARGET, file));
            throw new IllegalStateException("target failed after scheduling");
        });

        assertThatThrownBy(() -> job.run(CleanupMode.DELETE)).isInstanceOf(IllegalStateException.class);
        assertThat(file).exists();
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void schedulesNothingInADryRun() throws Exception {
        Path file = Files.writeString(tempDir.resolve("dry.bin"), "d");

        CleanupRunResult result = job(context -> {
            context.afterCommit(CleanupPostCommitAction.deleteFile(TARGET, file));
            return new CleanupTargetResult(TARGET, rowCount(), 0, 0, true, context.now());
        }).run(CleanupMode.DRY_RUN);

        assertThat(result.postCommit()).isEmpty();
        assertThat(file).exists();
        assertThat(rowCount()).isEqualTo(2);
    }

    private CleanupJob job(java.util.function.Function<CleanupTargetContext, CleanupTargetResult> body) {
        CleanupTarget target = new CleanupTarget() {
            @Override
            public String name() {
                return TARGET;
            }

            @Override
            public CleanupTargetResult run(CleanupTargetContext context) {
                return body.apply(context);
            }
        };
        return new CleanupJob(properties, dataSourceProvider, List.of(target), Clock.systemUTC());
    }

    private CleanupTargetResult result(CleanupTargetContext context, long deleted) {
        return new CleanupTargetResult(TARGET, deleted, deleted, 1, false, context.now());
    }

    private long rowCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM stored_file_rows", Map.of(), Long.class);
        return count == null ? 0 : count;
    }
}

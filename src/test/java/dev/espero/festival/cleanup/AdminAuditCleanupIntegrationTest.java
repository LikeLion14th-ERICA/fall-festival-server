package dev.espero.festival.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminAuditCleanupIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("b88b190d-ab07-42af-9ac2-9ea03b5aab5b");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

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
    private CleanupJob job;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private CleanupProperties properties;

    @Autowired
    private CleanupDataSourceProvider dataSourceProvider;

    private Instant oldEvent;
    private Instant recentEvent;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        oldEvent = now.minus(Duration.ofDays(730));
        recentEvent = now.minus(Duration.ofDays(30));
        properties.getDatasource().setRole(POSTGRES.getUsername());
        jdbc.update("DELETE FROM admin_audit_events", Map.of());
        jdbc.update("DELETE FROM admin_refresh_sessions", Map.of());
        jdbc.update("DELETE FROM admin_accounts", Map.of());
        jdbc.update("""
            INSERT INTO admin_accounts (
                id, username, password_hash, authority, enabled, created_at, updated_at, last_login_at
            ) VALUES (
                :id, 'cleanup-admin', 'test-only-password-hash', 'ADMIN', true, :createdAt, :createdAt, NULL
            )
            """, new MapSqlParameterSource()
            .addValue("id", ADMIN_ID)
            .addValue("createdAt", atUtc(recentEvent)));
    }

    @Test
    void dryRunReportsEligibleRowsWithoutDeletingThem() {
        insertEvents(1_001, oldEvent);
        insertEvents(1, recentEvent);

        CleanupRunResult result = job.run(CleanupMode.DRY_RUN);

        assertThat(result.status()).isEqualTo(CleanupRunStatus.COMPLETED);
        assertThat(result.mode()).isEqualTo(CleanupMode.DRY_RUN);
        assertThat(result.eligibleCount()).isEqualTo(1_001);
        assertThat(result.deletedCount()).isZero();
        assertThat(auditTarget(result)).satisfies(target -> {
            assertThat(target.target()).isEqualTo("admin_audit_events");
            assertThat(target.batches()).isEqualTo(3);
        });
        assertThat(auditCount()).isEqualTo(1_002);
    }

    @Test
    void deleteRunUsesOneFiveHundredRowBatchAndPreservesRecentEvents() {
        insertEvents(1_001, oldEvent);
        insertEvents(1, recentEvent);

        CleanupRunResult result = job.run(CleanupMode.DELETE);

        assertThat(result.status()).isEqualTo(CleanupRunStatus.COMPLETED);
        assertThat(result.mode()).isEqualTo(CleanupMode.DELETE);
        assertThat(result.eligibleCount()).isEqualTo(1_001);
        assertThat(result.deletedCount()).isEqualTo(500);
        assertThat(auditTarget(result))
            .extracting(CleanupTargetResult::batches)
            .isEqualTo(1);
        assertThat(auditCount()).isEqualTo(502);

        CleanupRunResult secondRun = job.run(CleanupMode.DELETE);
        CleanupRunResult finalRun = job.run(CleanupMode.DELETE);
        assertThat(secondRun.deletedCount()).isEqualTo(500);
        assertThat(finalRun.deletedCount()).isOne();
        assertThat(auditCount()).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE occurred_at = :occurredAt",
            new MapSqlParameterSource("occurredAt", atUtc(recentEvent)),
            Long.class
        )).isOne();
    }

    @Test
    void skipsWhenAnotherTransactionHoldsThePostgresAdvisoryLock() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DataSource dataSource = dataSourceProvider.dataSource();
        TransactionTemplate lockTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Future<?> holder = executor.submit(() -> lockTransaction.executeWithoutResult(status -> {
            NamedParameterJdbcTemplate lockJdbc = new NamedParameterJdbcTemplate(dataSource);
            lockJdbc.query(
                "SELECT pg_advisory_xact_lock(:lockKey)",
                Map.of("lockKey", properties.getAdvisoryLockKey()),
                resultSet -> null
            );
            lockAcquired.countDown();
            try {
                assertThat(releaseLock.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Advisory lock holder was interrupted", exception);
            }
        }));

        try {
            assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();
            CleanupRunResult result = job.run(CleanupMode.DRY_RUN);
            assertThat(result.status()).isEqualTo(CleanupRunStatus.SKIPPED_LOCK_NOT_ACQUIRED);
            assertThat(result.targets()).isEmpty();
        } finally {
            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void refusesDeleteWhenConfiguredRoleDoesNotMatchTheDedicatedConnectionUser() {
        properties.getDatasource().setRole("runtime-role-that-is-not-connected");

        CleanupRunResult result = job.run(CleanupMode.DELETE);

        assertThat(result.status()).isEqualTo(CleanupRunStatus.SKIPPED_UNSAFE_CONFIGURATION);
        assertThat(result.deletedCount()).isZero();
    }

    private void insertEvents(int count, Instant occurredAt) {
        for (int offset = 0; offset < count; offset += 200) {
            int end = Math.min(offset + 200, count);
            MapSqlParameterSource[] rows = new MapSqlParameterSource[end - offset];
            for (int index = offset; index < end; index++) {
                rows[index - offset] = new MapSqlParameterSource()
                    .addValue("id", UUID.nameUUIDFromBytes((occurredAt + ":" + index).getBytes()))
                    .addValue("adminId", ADMIN_ID)
                    .addValue("occurredAt", atUtc(occurredAt))
                    .addValue("requestId", "cleanup-" + occurredAt.getEpochSecond() + "-" + index);
            }
            jdbc.batchUpdate("""
                INSERT INTO admin_audit_events (
                    id, admin_id, action, resource_type, resource_id, occurred_at, request_id
                ) VALUES (
                    :id, :adminId, 'CROWDING_UPDATED', 'CROWDING', NULL, :occurredAt, :requestId
                )
                """, rows);
        }
    }

    private CleanupTargetResult auditTarget(CleanupRunResult result) {
        return result.targets().stream()
            .filter(target -> target.target().equals("admin_audit_events"))
            .findFirst()
            .orElseThrow();
    }

    private long auditCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_audit_events", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

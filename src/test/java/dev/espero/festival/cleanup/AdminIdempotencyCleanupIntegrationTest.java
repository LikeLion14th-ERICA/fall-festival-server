package dev.espero.festival.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminIdempotencyCleanupIntegrationTest {

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
    private CleanupJob job;

    @Autowired
    private CleanupProperties properties;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        properties.getDatasource().setRole(POSTGRES.getUsername());
        jdbc.update("DELETE FROM admin_idempotency_records", Map.of());
    }

    @Test
    void retainsInProgressAndRecentRecordsWhileRemovingCompletedRecordsInBoundedBatches() {
        Instant now = Instant.now();
        Instant oldCompleted = now.minus(Duration.ofHours(25));
        Instant recentCompleted = now.minus(Duration.ofHours(23));
        Instant oldInProgress = now.minus(Duration.ofDays(3));
        insertCompleted(501, oldCompleted);
        insertCompleted(1, recentCompleted);
        insertInProgress(oldInProgress);

        CleanupRunResult first = job.run(CleanupMode.DELETE);

        assertThat(target(first).eligibleCount()).isEqualTo(501);
        assertThat(target(first).deletedCount()).isEqualTo(500);
        assertThat(completedCount()).isEqualTo(2);
        assertThat(inProgressCount()).isOne();

        CleanupRunResult second = job.run(CleanupMode.DELETE);

        assertThat(target(second).eligibleCount()).isOne();
        assertThat(target(second).deletedCount()).isOne();
        assertThat(completedCount()).isOne();
        assertThat(inProgressCount()).isOne();
    }

    private CleanupTargetResult target(CleanupRunResult result) {
        return result.targets().stream()
            .filter(value -> value.target().equals("admin_idempotency_records"))
            .findFirst()
            .orElseThrow();
    }

    private void insertCompleted(int count, Instant completedAt) {
        for (int offset = 0; offset < count; offset += 200) {
            int end = Math.min(offset + 200, count);
            MapSqlParameterSource[] rows = new MapSqlParameterSource[end - offset];
            for (int index = offset; index < end; index++) {
                rows[index - offset] = baseRow(completedAt, index)
                    .addValue("state", "COMPLETED")
                    .addValue("leaseToken", null)
                    .addValue("leaseExpiresAt", null)
                    .addValue("responseStatus", 204)
                    .addValue("responseContentType", null)
                    .addValue("responseBody", null)
                    .addValue("completedAt", atUtc(completedAt));
            }
            jdbc.batchUpdate("""
                INSERT INTO admin_idempotency_records (
                    id, scope_hash, key_hash, request_fingerprint, state, lease_token, lease_expires_at,
                    response_status, response_content_type, response_body, created_at, updated_at, completed_at
                ) VALUES (
                    :id, :scopeHash, :keyHash, :fingerprint, :state, :leaseToken, :leaseExpiresAt,
                    :responseStatus, :responseContentType, :responseBody, :createdAt, :updatedAt, :completedAt
                )
                """, rows);
        }
    }

    private void insertInProgress(Instant createdAt) {
        MapSqlParameterSource row = baseRow(createdAt, 9_999)
            .addValue("state", "IN_PROGRESS")
            .addValue("leaseToken", UUID.randomUUID())
            .addValue("leaseExpiresAt", atUtc(createdAt.plus(Duration.ofHours(1))))
            .addValue("responseStatus", null)
            .addValue("responseContentType", null)
            .addValue("responseBody", null)
            .addValue("completedAt", null);
        jdbc.update("""
            INSERT INTO admin_idempotency_records (
                id, scope_hash, key_hash, request_fingerprint, state, lease_token, lease_expires_at,
                response_status, response_content_type, response_body, created_at, updated_at, completed_at
            ) VALUES (
                :id, :scopeHash, :keyHash, :fingerprint, :state, :leaseToken, :leaseExpiresAt,
                :responseStatus, :responseContentType, :responseBody, :createdAt, :updatedAt, :completedAt
            )
            """, row);
    }

    private MapSqlParameterSource baseRow(Instant timestamp, int ordinal) {
        return new MapSqlParameterSource()
            .addValue("id", UUID.nameUUIDFromBytes((timestamp + ":" + ordinal).getBytes(StandardCharsets.UTF_8)))
            .addValue("scopeHash", hash("scope:" + timestamp + ":" + ordinal))
            .addValue("keyHash", hash("key:" + timestamp + ":" + ordinal))
            .addValue("fingerprint", hash("fingerprint:" + timestamp + ":" + ordinal))
            .addValue("createdAt", atUtc(timestamp))
            .addValue("updatedAt", atUtc(timestamp));
    }

    private long completedCount() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM admin_idempotency_records WHERE state = 'COMPLETED'", Map.of(), Long.class
        );
        return count == null ? 0 : count;
    }

    private long inProgressCount() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM admin_idempotency_records WHERE state = 'IN_PROGRESS'", Map.of(), Long.class
        );
        return count == null ? 0 : count;
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

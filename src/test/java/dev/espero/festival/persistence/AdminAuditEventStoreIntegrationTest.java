package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditEvent;
import dev.espero.festival.domain.AdminAuditResourceType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminAuditEventStoreIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("b88b190d-ab07-42af-9ac2-9ea03b5aab5b");
    private static final UUID UNKNOWN_ADMIN_ID = UUID.fromString("ea42c927-7002-43c8-a11e-4b9167842a02");
    private static final Instant CREATED_AT = Instant.parse("2030-09-29T01:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2030-09-29T02:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AdminAuditEventStore store;

    @Autowired
    private AdminAuditService service;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM admin_audit_events", Map.of());
        jdbc.update("DELETE FROM admin_refresh_sessions", Map.of());
        jdbc.update("DELETE FROM admin_accounts", Map.of());
        insertAdmin(ADMIN_ID);
        authenticate(ADMIN_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void v1ThroughV12MigrateAndStorePersistsEveryFieldIncludingANullResourceId() {
        assertThat(latestMigrationVersion()).isEqualTo("12");
        UUID eventId = UUID.randomUUID();
        store.insert(new AdminAuditEvent(
            eventId, ADMIN_ID, AdminAuditAction.CROWDING_UPDATED, AdminAuditResourceType.CROWDING,
            null, CHANGED_AT, "request.integration-1"
        ));

        AuditRow row = auditRow(eventId);
        assertThat(row.adminId()).isEqualTo(ADMIN_ID);
        assertThat(row.action()).isEqualTo("CROWDING_UPDATED");
        assertThat(row.resourceType()).isEqualTo("CROWDING");
        assertThat(row.resourceId()).isNull();
        assertThat(row.occurredAt()).isEqualTo(CHANGED_AT);
        assertThat(row.requestId()).isEqualTo("request.integration-1");
    }

    @Test
    void rejectsUnknownAdminsAndRestrictsDeletingAnAuditedAdmin() {
        AdminAuditEvent unknownAdminEvent = new AdminAuditEvent(
            UUID.randomUUID(), UUID.randomUUID(), AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING, null, CHANGED_AT, "request-unknown-admin"
        );
        assertThatThrownBy(() -> store.insert(unknownAdminEvent))
            .isInstanceOf(DataIntegrityViolationException.class);

        store.insert(event("request-delete-restrict"));
        assertThatThrownBy(() -> jdbc.update(
            "DELETE FROM admin_accounts WHERE id = :adminId",
            new MapSqlParameterSource("adminId", ADMIN_ID)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseConstraintsRejectMalformedActionsResourcesAndRequestIds() {
        assertConstraintViolation("", "CROWDING", null, "request-valid-0");
        assertConstraintViolation("lowercase", "CROWDING", null, "request-valid-1");
        assertConstraintViolation("CROWDING__UPDATED", "CROWDING", null, "request-valid-1b");
        assertConstraintViolation("CROWDING_UPDATED", "", null, "request-valid-2");
        assertConstraintViolation("CROWDING_UPDATED", "lowercase", null, "request-valid-2b");
        for (String resourceId : new String[] {" ", "   ", "\t", "\n", "\r\n", " \t\n "}) {
            assertConstraintViolation("CROWDING_UPDATED", "CROWDING", resourceId, "request-blank-resource");
        }
        assertConstraintViolation(
            "CROWDING_UPDATED", "CROWDING", "a".repeat(129), "request-long-resource"
        );
        assertConstraintViolation("CROWDING_UPDATED", "CROWDING", null, "");
        assertConstraintViolation("CROWDING_UPDATED", "CROWDING", null, "invalid/request");
        assertConstraintViolation("CROWDING_UPDATED", "CROWDING", null, "a".repeat(129));
    }

    @Test
    void resourceIdConstraintAllowsNullAndValuesContainingNonWhitespaceCharacters() {
        String[] resourceIds = {null, "abc", " abc ", "2026-09-29"};
        for (int index = 0; index < resourceIds.length; index++) {
            store.insert(new AdminAuditEvent(
                UUID.randomUUID(),
                ADMIN_ID,
                AdminAuditAction.CROWDING_UPDATED,
                AdminAuditResourceType.CROWDING,
                resourceIds[index],
                CHANGED_AT,
                "request-resource-" + index
            ));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_events", Map.of(), Long.class))
            .isEqualTo(4L);
    }

    @Test
    void idAndOccurredAtHaveNoDatabaseDefaults() {
        Map<String, Object> defaults = jdbc.query(
            """
            SELECT column_name, column_default
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'admin_audit_events'
              AND column_name IN ('id', 'occurred_at')
            """,
            Map.of(),
            resultSet -> {
                java.util.HashMap<String, Object> values = new java.util.HashMap<>();
                while (resultSet.next()) {
                    values.put(resultSet.getString("column_name"), resultSet.getObject("column_default"));
                }
                return values;
            }
        );
        assertThat(defaults).containsOnlyKeys("id", "occurred_at");
        assertThat(defaults.get("id")).isNull();
        assertThat(defaults.get("occurred_at")).isNull();
    }

    @Test
    void requiresAnExistingBusinessTransaction() {
        assertThatThrownBy(() -> service.record(
            AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING,
            "student-zone",
            "request-no-transaction"
        )).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void commitsBusinessAndAuditWritesTogether() {
        transactions.executeWithoutResult(status -> {
            updateBusinessRow(CHANGED_AT);
            service.record(
                AdminAuditAction.CROWDING_UPDATED, AdminAuditResourceType.CROWDING,
                "student-zone", "request-commit"
            );
        });

        assertThat(lastLoginAt()).isEqualTo(CHANGED_AT);
        assertThat(auditCount("request-commit")).isOne();
    }

    @Test
    void rollsBackBusinessWriteWhenTheProductionAuditStoreRejectsAnUnknownAdmin() {
        authenticate(UNKNOWN_ADMIN_ID);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            updateBusinessRow(CHANGED_AT);
            service.record(
                AdminAuditAction.CROWDING_UPDATED,
                AdminAuditResourceType.CROWDING,
                "student-zone",
                "request-audit-failure"
            );
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(lastLoginAt()).isNull();
        assertThat(auditCount("request-audit-failure")).isZero();
    }

    @Test
    void rollsBackAuditWhenBusinessTransactionFailsAfterTheInsert() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            updateBusinessRow(CHANGED_AT);
            service.record(
                AdminAuditAction.CROWDING_UPDATED, AdminAuditResourceType.CROWDING,
                "student-zone", "request-post-audit-failure"
            );
            throw new IllegalStateException("simulated business failure after audit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(lastLoginAt()).isNull();
        assertThat(auditCount("request-post-audit-failure")).isZero();
    }

    private void insertAdmin(UUID adminId) {
        jdbc.update("""
            INSERT INTO admin_accounts (
                id, username, password_hash, authority, enabled, created_at, updated_at, last_login_at
            ) VALUES (
                :id, 'audit-admin', 'test-only-password-hash', 'ADMIN', true, :createdAt, :createdAt, NULL
            )
            """, new MapSqlParameterSource()
            .addValue("id", adminId)
            .addValue("createdAt", atUtc(CREATED_AT)));
    }

    private AdminAuditEvent event(String requestId) {
        return new AdminAuditEvent(
            UUID.randomUUID(), ADMIN_ID, AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING, "student-zone", CHANGED_AT, requestId
        );
    }

    private void assertConstraintViolation(
        String action,
        String resourceType,
        String resourceId,
        String requestId
    ) {
        assertThatThrownBy(() -> insertRawAudit(action, resourceType, resourceId, requestId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRawAudit(String action, String resourceType, String resourceId, String requestId) {
        jdbc.update("""
            INSERT INTO admin_audit_events (
                id, admin_id, action, resource_type, resource_id, occurred_at, request_id
            ) VALUES (
                :id, :adminId, :action, :resourceType, :resourceId, :occurredAt, :requestId
            )
            """, new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("adminId", ADMIN_ID)
            .addValue("action", action)
            .addValue("resourceType", resourceType)
            .addValue("resourceId", resourceId)
            .addValue("occurredAt", atUtc(CHANGED_AT))
            .addValue("requestId", requestId));
    }

    private void updateBusinessRow(Instant changedAt) {
        jdbc.update("""
            UPDATE admin_accounts
            SET last_login_at = :changedAt, updated_at = :changedAt
            WHERE id = :adminId
            """, new MapSqlParameterSource()
            .addValue("adminId", ADMIN_ID)
            .addValue("changedAt", atUtc(changedAt)));
    }

    private void authenticate(UUID adminId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(adminId, "audit-admin", "ADMIN"),
                null,
                List.of(new SimpleGrantedAuthority("ADMIN"))
            )
        );
    }

    private Instant lastLoginAt() {
        OffsetDateTime value = jdbc.queryForObject(
            "SELECT last_login_at FROM admin_accounts WHERE id = :adminId",
            new MapSqlParameterSource("adminId", ADMIN_ID),
            OffsetDateTime.class
        );
        return value == null ? null : value.toInstant();
    }

    private long auditCount(String requestId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_events WHERE request_id = :requestId",
            new MapSqlParameterSource("requestId", requestId), Long.class
        );
        return count == null ? 0 : count;
    }

    private String latestMigrationVersion() {
        return jdbc.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
            Map.of(), String.class
        );
    }

    private AuditRow auditRow(UUID eventId) {
        return jdbc.queryForObject("""
            SELECT admin_id, action, resource_type, resource_id, occurred_at, request_id
            FROM admin_audit_events
            WHERE id = :id
            """, new MapSqlParameterSource("id", eventId), (resultSet, rowNumber) -> new AuditRow(
            resultSet.getObject("admin_id", UUID.class),
            resultSet.getString("action"),
            resultSet.getString("resource_type"),
            resultSet.getString("resource_id"),
            resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
            resultSet.getString("request_id")
        ));
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record AuditRow(
        UUID adminId,
        String action,
        String resourceType,
        String resourceId,
        Instant occurredAt,
        String requestId
    ) {}
}

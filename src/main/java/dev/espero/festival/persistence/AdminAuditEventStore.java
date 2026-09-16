package dev.espero.festival.persistence;

import dev.espero.festival.domain.AdminAuditEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("db")
public class AdminAuditEventStore {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminAuditEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AdminAuditEvent event) {
        jdbc.update("""
            INSERT INTO admin_audit_events (
                id, admin_id, action, resource_type, resource_id, occurred_at, request_id
            ) VALUES (
                :id, :adminId, :action, :resourceType, :resourceId, :occurredAt, :requestId
            )
            """, new MapSqlParameterSource()
            .addValue("id", event.id())
            .addValue("adminId", event.adminId())
            .addValue("action", event.action().name())
            .addValue("resourceType", event.resourceType().name())
            .addValue("resourceId", event.resourceId())
            .addValue("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
            .addValue("requestId", event.requestId()));
    }
}

package dev.espero.festival.auth;

import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditEvent;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.persistence.AdminAuditEventStore;
import dev.espero.festival.web.RequestIdPolicy;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class AdminAuditService {

    private final AdminAuditEventStore store;
    private final AdminContext adminContext;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    @Autowired
    public AdminAuditService(AdminAuditEventStore store, AdminContext adminContext, Clock clock) {
        this(store, adminContext, clock, UUID::randomUUID);
    }

    AdminAuditService(
        AdminAuditEventStore store,
        AdminContext adminContext,
        Clock clock,
        Supplier<UUID> idGenerator
    ) {
        this.store = store;
        this.adminContext = adminContext;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
        AdminAuditAction action,
        AdminAuditResourceType resourceType,
        String resourceId,
        String requestId
    ) {
        Objects.requireNonNull(action, "Audit action is required");
        Objects.requireNonNull(resourceType, "Audit resource type is required");
        if (resourceId != null && (resourceId.isBlank() || resourceId.length() > 128)) {
            throw new IllegalArgumentException("Audit resource id must be null or 1 to 128 non-blank characters");
        }
        if (!RequestIdPolicy.isValid(requestId)) {
            throw new IllegalArgumentException("Audit request id is invalid");
        }

        AdminPrincipal principal = adminContext.requireCurrent();
        store.insert(new AdminAuditEvent(
            idGenerator.get(),
            principal.adminId(),
            action,
            resourceType,
            resourceId,
            clock.instant(),
            requestId
        ));
    }
}

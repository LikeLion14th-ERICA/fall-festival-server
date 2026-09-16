package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditEvent;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.persistence.AdminAuditEventStore;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class AdminAuditServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("7309ad07-bdf0-42ff-a547-155ce180e117");
    private static final UUID EVENT_ID = UUID.fromString("f5eb7875-ee9c-4924-95d6-6df54dcbb804");
    private static final Instant NOW = Instant.parse("2030-09-29T03:00:00Z");

    @Mock
    private AdminAuditEventStore store;

    @Mock
    private AdminContext adminContext;

    private AdminAuditService service;

    @BeforeEach
    void setUp() {
        service = new AdminAuditService(
            store, adminContext, Clock.fixed(NOW, ZoneOffset.UTC), () -> EVENT_ID
        );
        lenient().when(adminContext.requireCurrent()).thenReturn(new AdminPrincipal(ADMIN_ID, "admin", "ADMIN"));
    }

    @Test
    void recordsTheAuthenticatedAdminWithGeneratedIdClockAndTypedValues() {
        service.record(
            AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING,
            "student-zone",
            "request-123"
        );

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(store).insert(event.capture());
        assertThat(event.getValue()).isEqualTo(new AdminAuditEvent(
            EVENT_ID,
            ADMIN_ID,
            AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING,
            "student-zone",
            NOW,
            "request-123"
        ));
    }

    @Test
    void allowsANullResourceId() {
        service.record(
            AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING,
            null,
            "request-null-resource"
        );

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(store).insert(event.capture());
        assertThat(event.getValue().resourceId()).isNull();
    }

    @Test
    void rejectsBlankOrOversizedResourceIdsBeforeReadingTheAdminContext() {
        assertThatThrownBy(() -> service.record(
            AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING,
            "   ",
            "request-blank-resource"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(
            AdminAuditAction.CROWDING_UPDATED,
            AdminAuditResourceType.CROWDING,
            "a".repeat(129),
            "request-long-resource"
        )).isInstanceOf(IllegalArgumentException.class);

        verify(store, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(adminContext, never()).requireCurrent();
    }

    @Test
    void rejectsRequestIdsThatDoNotMatchTheResponseRequestIdPolicy() {
        for (String requestId : new String[] {null, "", " leading-space", "invalid/value", "a".repeat(129)}) {
            assertThatThrownBy(() -> service.record(
                AdminAuditAction.CROWDING_UPDATED, AdminAuditResourceType.CROWDING, null, requestId
            )).isInstanceOf(IllegalArgumentException.class);
        }

        verify(store, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(adminContext, never()).requireCurrent();
    }

    @Test
    void doesNotExposeActorOrSecretBearingParametersAndRequiresAnExistingTransaction() throws Exception {
        Class<?>[] parameterTypes = Arrays.stream(AdminAuditService.class.getMethod(
            "record",
            AdminAuditAction.class,
            AdminAuditResourceType.class,
            String.class,
            String.class
        ).getParameterTypes()).toArray(Class<?>[]::new);
        assertThat(parameterTypes).doesNotContain(UUID.class, AdminPrincipal.class);

        assertThat(Arrays.stream(AdminAuditEvent.class.getRecordComponents())
            .map(RecordComponent::getName))
            .containsExactly("id", "adminId", "action", "resourceType", "resourceId", "occurredAt", "requestId")
            .doesNotContain("username", "authority", "metadata", "password", "token", "cookie");

        Transactional transactional = AdminAuditService.class.getMethod(
            "record",
            AdminAuditAction.class,
            AdminAuditResourceType.class,
            String.class,
            String.class
        ).getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}

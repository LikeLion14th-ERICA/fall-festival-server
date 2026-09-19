package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.idempotency.CanonicalPayload;
import dev.espero.festival.idempotency.IdempotencyKeyPolicy;
import dev.espero.festival.idempotency.IdempotencyRequest;
import dev.espero.festival.idempotency.IdempotencyResponse;
import dev.espero.festival.persistence.CrowdingStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public and authenticated administrator crowding endpoints. */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class CrowdingController {

    private static final String ADMIN_ROUTE = "/api/v2/admin/crowding";

    private final CrowdingViewService views;
    private final CrowdingStore store;
    private final ConditionalResponseSupport conditionalResponses;
    private final AdminMutationPreconditions mutationPreconditions;
    private final AdminIdempotencyService idempotency;
    private final AdminAuditService audit;
    private final AdminContext adminContext;
    private final Clock clock;
    private final CatalogSnapshotProvider snapshots;

    public CrowdingController(
        CrowdingViewService views,
        CrowdingStore store,
        ConditionalResponseSupport conditionalResponses,
        AdminMutationPreconditions mutationPreconditions,
        AdminIdempotencyService idempotency,
        AdminAuditService audit,
        AdminContext adminContext,
        Clock clock,
        CatalogSnapshotProvider snapshots
    ) {
        this.views = views;
        this.store = store;
        this.conditionalResponses = conditionalResponses;
        this.mutationPreconditions = mutationPreconditions;
        this.idempotency = idempotency;
        this.audit = audit;
        this.adminContext = adminContext;
        this.clock = clock;
        this.snapshots = snapshots;
    }

    @GetMapping("/crowding")
    public ResponseEntity<ConditionalApiResponse<CrowdingResponse>> getCrowding(HttpServletRequest request) {
        validatePublicQuery(request);
        CrowdingViewService.CrowdingSnapshot snapshot = views.current(request);
        return conditionalResponses.respond(request, snapshot.response(), snapshot.meta());
    }

    @GetMapping("/admin/crowding")
    public ResponseEntity<ConditionalApiResponse<CrowdingResponse>> getAdminCrowding(
        HttpServletRequest request
    ) {
        validateAdminQuery(request);
        CrowdingViewService.CrowdingSnapshot snapshot = views.current(request);
        return conditionalResponses.respond(request, snapshot.response(), snapshot.meta());
    }

    @PutMapping("/admin/crowding")
    @AdminMutationPolicy(AdminMutationConcurrency.IF_MATCH_REQUIRED)
    public ResponseEntity<Void> putAdminCrowding(
        HttpServletRequest request,
        @RequestBody CrowdingInput input
    ) {
        validateHeaders(request);
        CrowdingLevel level = validateInput(input);
        CrowdingViewService.CrowdingSnapshot snapshot = views.current(request);
        if (!views.isActualFestivalDay(snapshot)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "NOT_FESTIVAL_DAY",
                "현재 날짜는 축제 운영일이 아닙니다.",
                false
            );
        }

        AdminPrincipal principal = adminContext.requireCurrent();
        String idempotencyKey = singleHeader(request, "Idempotency-Key");
        String resourceId = snapshot.context().festivalId() + "/" + snapshot.operatingDay();
        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(),
            "PUT",
            ADMIN_ROUTE,
            resourceId,
            idempotencyKey,
            CanonicalPayload.from(Map.of(
                "confirmFull", Boolean.TRUE.equals(input.confirmFull()),
                "level", level.name()
            ))
        );

        idempotency.execute(idempotencyRequest, () -> {
            Optional<dev.espero.festival.domain.CrowdingRecord> current = store.findForUpdate(
                snapshot.context().festivalId(),
                snapshot.operatingDay()
            );
            Instant now = clock.instant();
            CrowdingViewService.CrowdingSnapshot currentSnapshot = views.withSaved(
                request,
                snapshot.context(),
                snapshot.schedules(),
                snapshot.today(),
                snapshot.schedule(),
                current,
                now
            );
            mutationPreconditions.requireCurrentRepresentation(
                request,
                AdminMutationConcurrency.IF_MATCH_REQUIRED,
                currentSnapshot.etag()
            );
            CrowdingStore.CrowdingMutation mutation = store.save(
                snapshot.context().festivalId(),
                snapshot.operatingDay(),
                level.name(),
                now
            );
            if (mutation.changed()) {
                audit.record(
                    AdminAuditAction.CROWDING_UPDATED,
                    AdminAuditResourceType.CROWDING,
                    resourceId,
                    ApiMetaSupport.resolveRequestId(request)
                );
            }
            return IdempotencyResponse.noContent();
        });
        return ResponseEntity.noContent().build();
    }

    private CrowdingLevel validateInput(CrowdingInput input) {
        if (input == null || input.level() == null || input.level().isBlank()) {
            throw validation();
        }
        final CrowdingLevel level;
        try {
            level = CrowdingLevel.valueOf(input.level());
        } catch (IllegalArgumentException exception) {
            throw validation();
        }
        if (level == CrowdingLevel.FULL && !Boolean.TRUE.equals(input.confirmFull())) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CONFIRMATION_REQUIRED",
                "만석 변경 확인이 필요합니다.",
                false
            );
        }
        return level;
    }

    private void validateHeaders(HttpServletRequest request) {
        List<String> ifMatch = Collections.list(request.getHeaders("If-Match"));
        if (ifMatch.isEmpty()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "PRECONDITION_REQUIRED",
                "최신 상태를 확인한 뒤 다시 저장해 주세요.",
                false
            );
        }
        if (ifMatch.size() != 1 || !ifMatch.getFirst().trim().matches("^\\\"[0-9a-f]{64}\\\"$")) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_IF_MATCH",
                "If-Match 헤더 형식이 올바르지 않습니다.",
                false
            );
        }
        List<String> idempotencyKeys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (idempotencyKeys.isEmpty()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key 헤더가 필요합니다.",
                false
            );
        }
        if (idempotencyKeys.size() != 1 || !IdempotencyKeyPolicy.isValid(idempotencyKeys.getFirst())) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key 헤더 형식이 올바르지 않습니다.",
                false
            );
        }
    }

    private String singleHeader(HttpServletRequest request, String name) {
        return Collections.list(request.getHeaders(name)).getFirst();
    }

    private ApiException validation() {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "VALIDATION_FAILED",
            "요청 필드를 확인해 주세요.",
            false
        );
    }

    private void validatePublicQuery(HttpServletRequest request) {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
    }

    private void validateAdminQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_QUERY",
                "요청 파라미터를 확인해 주세요.",
                false
            );
        }
    }

    public record CrowdingInput(String level, Boolean confirmFull) {}

    public enum CrowdingLevel {
        RELAXED,
        MODERATE,
        CROWDED,
        FULL
    }
}

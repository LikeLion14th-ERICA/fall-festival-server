package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.domain.GoodsAvailability;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.idempotency.CanonicalPayload;
import dev.espero.festival.idempotency.IdempotencyExecution;
import dev.espero.festival.idempotency.IdempotencyKeyPolicy;
import dev.espero.festival.idempotency.IdempotencyRequest;
import dev.espero.festival.idempotency.IdempotencyResponse;
import dev.espero.festival.persistence.GoodsStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Authenticated administrator mutations for goods availability. */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class AdminGoodsController {

    private static final String AVAILABILITY_ROUTE =
        "/api/v2/admin/goods/{goodsId}/combinations/{combinationId}/availability";

    private final GoodsStore store;
    private final GoodsViewService views;
    private final AdminGoodsViewService adminViews;
    private final ConditionalResponseSupport conditionalResponses;
    private final FestivalProperties properties;
    private final AdminIdempotencyService idempotency;
    private final AdminAuditService audit;
    private final AdminContext adminContext;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AdminGoodsController(
        GoodsStore store,
        GoodsViewService views,
        AdminGoodsViewService adminViews,
        ConditionalResponseSupport conditionalResponses,
        FestivalProperties properties,
        AdminIdempotencyService idempotency,
        AdminAuditService audit,
        AdminContext adminContext,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.store = store;
        this.views = views;
        this.adminViews = adminViews;
        this.conditionalResponses = conditionalResponses;
        this.properties = properties;
        this.idempotency = idempotency;
        this.audit = audit;
        this.adminContext = adminContext;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/admin/goods")
    public ResponseEntity<ApiResponse<GoodsAvailabilityListResponse>> getAdminGoods(HttpServletRequest request) {
        validateQuery(request);
        GoodsViewService.GoodsAvailabilityListSnapshot snapshot = views.availabilityList(request);
        return ResponseEntity.ok(new ApiResponse<>(snapshot.response(), snapshot.meta()));
    }

    @GetMapping("/admin/products")
    public ResponseEntity<ApiResponse<AdminGoodsListResponse>> getAdminProducts(HttpServletRequest request) {
        validateQuery(request);
        AdminGoodsViewService.AdminGoodsListSnapshot snapshot = adminViews.list(request);
        return ResponseEntity.ok(new ApiResponse<>(snapshot.response(), snapshot.meta()));
    }

    @GetMapping("/admin/products/{goodsId}")
    public ResponseEntity<ConditionalApiResponse<AdminGoodsResponse>> getAdminProduct(
        HttpServletRequest request,
        @PathVariable UUID goodsId
    ) {
        validateQuery(request);
        AdminGoodsViewService.AdminGoodsSnapshot snapshot = adminViews.find(request, goodsId);
        return conditionalResponses.respond(request, snapshot.response(), snapshot.meta());
    }

    @PutMapping("/admin/goods/{goodsId}/combinations/{combinationId}/availability")
    @AdminMutationPolicy(AdminMutationConcurrency.LAST_WRITE_WINS)
    public ResponseEntity<String> putAdminAvailability(
        HttpServletRequest request,
        @PathVariable UUID goodsId,
        @PathVariable UUID combinationId,
        @RequestBody GoodsAvailabilityInput input
    ) {
        validateQuery(request);
        GoodsAvailability availability = validate(input);
        String idempotencyKey = requireIdempotencyKey(request);
        AdminPrincipal principal = adminContext.requireCurrent();
        UUID festivalId = properties.configuredFestivalId();
        String auditResourceId = goodsId + "/" + combinationId;
        String idempotencyResourceId = availabilityIdempotencyResourceId(festivalId, goodsId, combinationId);

        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(),
            "PUT",
            AVAILABILITY_ROUTE,
            idempotencyResourceId,
            idempotencyKey,
            CanonicalPayload.from(Map.of("status", availability.name()))
        );

        IdempotencyExecution execution = idempotency.execute(idempotencyRequest, () -> {
            boolean updated = store.updateAvailability(
                festivalId,
                goodsId,
                combinationId,
                availability,
                clock.instant()
            );
            if (!updated) {
                throw notFound();
            }
            GoodsViewService.GoodsAvailabilitySnapshot snapshot = views.availability(request, goodsId);
            audit.record(
                AdminAuditAction.GOODS_AVAILABILITY_UPDATED,
                AdminAuditResourceType.GOODS,
                auditResourceId,
                ApiMetaSupport.resolveRequestId(request)
            );
            String json = objectMapper.writeValueAsString(new ApiResponse<>(snapshot.response(), snapshot.meta()));
            return new IdempotencyResponse(200, MediaType.APPLICATION_JSON_VALUE, json);
        });

        IdempotencyResponse response = execution.response();
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    static String availabilityIdempotencyResourceId(UUID festivalId, UUID goodsId, UUID combinationId) {
        return festivalId + "/" + goodsId + "/" + combinationId;
    }

    private GoodsAvailability validate(GoodsAvailabilityInput input) {
        if (input == null || input.hasUnexpectedFields() || input.status() == null || input.status().isBlank()) {
            throw validation();
        }
        try {
            return GoodsAvailability.valueOf(input.status());
        } catch (IllegalArgumentException exception) {
            throw validation();
        }
    }

    private String requireIdempotencyKey(HttpServletRequest request) {
        List<String> keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key 헤더가 필요합니다.",
                false
            );
        }
        if (keys.size() != 1 || !IdempotencyKeyPolicy.isValid(keys.getFirst())) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key 헤더 형식이 올바르지 않습니다.",
                false
            );
        }
        return keys.getFirst();
    }

    private void validateQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
        }
    }

    private ApiException validation() {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "VALIDATION_FAILED",
            "요청 필드를 확인해 주세요.",
            false
        );
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.", false);
    }
}

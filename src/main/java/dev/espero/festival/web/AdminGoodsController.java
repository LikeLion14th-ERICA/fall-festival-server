package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsAvailability;
import dev.espero.festival.goods.GoodsCreationService;
import dev.espero.festival.goods.GoodsDeletionService;
import dev.espero.festival.goods.GoodsOptionIdConflictException;
import dev.espero.festival.goods.GoodsUpdateService;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.idempotency.CanonicalPayload;
import dev.espero.festival.idempotency.IdempotencyExecution;
import dev.espero.festival.idempotency.IdempotencyKeyPolicy;
import dev.espero.festival.idempotency.IdempotencyRequest;
import dev.espero.festival.idempotency.IdempotencyResponse;
import dev.espero.festival.media.UnavailableGoodsImageException;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Authenticated administrator mutations for goods availability. */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class AdminGoodsController {

    private static final String AVAILABILITY_ROUTE =
        "/api/v2/admin/goods/{goodsId}/combinations/{combinationId}/availability";
    private static final String PRODUCT_POST_ROUTE = "/api/v2/admin/products";
    private static final String PRODUCT_ITEM_ROUTE = "/api/v2/admin/products/{goodsId}";

    private final GoodsStore store;
    private final GoodsViewService views;
    private final AdminGoodsViewService adminViews;
    private final GoodsCreationService goodsCreation;
    private final GoodsUpdateService goodsUpdate;
    private final GoodsDeletionService goodsDeletion;
    private final ConditionalResponseSupport conditionalResponses;
    private final AdminMutationPreconditions mutationPreconditions;
    private final FestivalProperties properties;
    private final AdminIdempotencyService idempotency;
    private final AdminAuditService audit;
    private final AdminContext adminContext;
    private final Clock clock;
    private final ApiMetaSupport metaSupport;
    private final ObjectMapper objectMapper;

    public AdminGoodsController(
        GoodsStore store,
        GoodsViewService views,
        AdminGoodsViewService adminViews,
        GoodsCreationService goodsCreation,
        GoodsUpdateService goodsUpdate,
        GoodsDeletionService goodsDeletion,
        ConditionalResponseSupport conditionalResponses,
        AdminMutationPreconditions mutationPreconditions,
        FestivalProperties properties,
        AdminIdempotencyService idempotency,
        AdminAuditService audit,
        AdminContext adminContext,
        Clock clock,
        ApiMetaSupport metaSupport,
        ObjectMapper objectMapper
    ) {
        this.store = store;
        this.views = views;
        this.adminViews = adminViews;
        this.goodsCreation = goodsCreation;
        this.goodsUpdate = goodsUpdate;
        this.goodsDeletion = goodsDeletion;
        this.conditionalResponses = conditionalResponses;
        this.mutationPreconditions = mutationPreconditions;
        this.properties = properties;
        this.idempotency = idempotency;
        this.audit = audit;
        this.adminContext = adminContext;
        this.clock = clock;
        this.metaSupport = metaSupport;
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

    @PostMapping(path = "/admin/products", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AdminGoodsResponse>> postAdminProduct(
        HttpServletRequest request,
        @RequestBody GoodsInput input
    ) {
        validateQuery(request);
        GoodsInputValidator.validate(input);
        String idempotencyKey = requireIdempotencyKey(request);
        AdminPrincipal principal = adminContext.requireCurrent();
        UUID festivalId = properties.configuredFestivalId();

        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(),
            "POST",
            PRODUCT_POST_ROUTE,
            productIdempotencyResourceId(festivalId),
            idempotencyKey,
            GoodsInputCanonicalPayload.from(input)
        );

        IdempotencyExecution execution;
        try {
            execution = idempotency.execute(idempotencyRequest, () -> {
                Goods created = goodsCreation.create(festivalId, input, clock.instant());
                AdminGoodsViewService.AdminGoodsSnapshot snapshot = adminViews.snapshot(request, created);
                audit.record(
                    AdminAuditAction.PRODUCT_CREATED,
                    AdminAuditResourceType.GOODS,
                    created.id().toString(),
                    ApiMetaSupport.resolveRequestId(request)
                );
                String json = objectMapper.writeValueAsString(new ApiResponse<>(snapshot.response(), snapshot.meta()));
                return new IdempotencyResponse(HttpStatus.CREATED.value(), MediaType.APPLICATION_JSON_VALUE, json);
            });
        } catch (UnavailableGoodsImageException exception) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_MEDIA_REFERENCE",
                "사용할 수 없는 상품 이미지가 포함되어 있습니다.",
                false
            );
        }

        AdminGoodsResponse data = storedGoods(execution.response());
        return ResponseEntity.status(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiResponse<>(data, metaSupport.unscopedMeta(request, "ko")));
    }

    @PutMapping(path = "/admin/products/{goodsId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AdminMutationPolicy(AdminMutationConcurrency.IF_MATCH_REQUIRED)
    public ResponseEntity<ApiResponse<AdminGoodsResponse>> putAdminProduct(
        HttpServletRequest request,
        @PathVariable UUID goodsId,
        @RequestBody GoodsInput input
    ) {
        validateQuery(request);
        GoodsInputValidator.validate(input);
        String idempotencyKey = requireIdempotencyKey(request);
        String ifMatch = mutationPreconditions.requireValidIfMatch(request);
        AdminPrincipal principal = adminContext.requireCurrent();
        UUID festivalId = properties.configuredFestivalId();

        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(),
            "PUT",
            PRODUCT_ITEM_ROUTE,
            productItemResourceId(festivalId, goodsId),
            idempotencyKey,
            GoodsInputCanonicalPayload.from(input, ifMatch)
        );

        IdempotencyExecution execution;
        try {
            execution = idempotency.execute(idempotencyRequest, () -> {
                Goods current = store.findForUpdate(festivalId, goodsId).orElseThrow(AdminGoodsController::notFound);
                AdminGoodsViewService.AdminGoodsSnapshot currentSnapshot = adminViews.snapshot(request, current);
                mutationPreconditions.requireCurrentRepresentation(
                    ifMatch, AdminMutationConcurrency.IF_MATCH_REQUIRED, currentSnapshot.etag()
                );
                Goods updated = goodsUpdate.update(festivalId, current, input, clock.instant());
                AdminGoodsViewService.AdminGoodsSnapshot updatedSnapshot = adminViews.snapshot(request, updated);
                audit.record(
                    AdminAuditAction.PRODUCT_UPDATED,
                    AdminAuditResourceType.GOODS,
                    goodsId.toString(),
                    ApiMetaSupport.resolveRequestId(request)
                );
                String json = objectMapper.writeValueAsString(
                    new ApiResponse<>(updatedSnapshot.response(), updatedSnapshot.meta())
                );
                return new IdempotencyResponse(HttpStatus.OK.value(), MediaType.APPLICATION_JSON_VALUE, json);
            });
        } catch (UnavailableGoodsImageException exception) {
            throw invalidMediaReference();
        } catch (GoodsOptionIdConflictException exception) {
            throw validation();
        }

        return ResponseEntity.ok(new ApiResponse<>(
            storedGoods(execution.response()),
            metaSupport.unscopedMeta(request, "ko")
        ));
    }

    @DeleteMapping("/admin/products/{goodsId}")
    @AdminMutationPolicy(AdminMutationConcurrency.IF_MATCH_REQUIRED)
    public ResponseEntity<ApiResponse<GoodsDeletedResponse>> deleteAdminProduct(
        HttpServletRequest request,
        @PathVariable UUID goodsId
    ) {
        validateQuery(request);
        String idempotencyKey = requireIdempotencyKey(request);
        String ifMatch = mutationPreconditions.requireValidIfMatch(request);
        AdminPrincipal principal = adminContext.requireCurrent();
        UUID festivalId = properties.configuredFestivalId();

        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(),
            "DELETE",
            PRODUCT_ITEM_ROUTE,
            productItemResourceId(festivalId, goodsId),
            idempotencyKey,
            CanonicalPayload.from(Map.of("ifMatch", ifMatch))
        );
        IdempotencyExecution execution = idempotency.execute(idempotencyRequest, () -> {
            Goods current = store.findForUpdate(festivalId, goodsId).orElseThrow(AdminGoodsController::notFound);
            AdminGoodsViewService.AdminGoodsSnapshot currentSnapshot = adminViews.snapshot(request, current);
            mutationPreconditions.requireCurrentRepresentation(
                ifMatch, AdminMutationConcurrency.IF_MATCH_REQUIRED, currentSnapshot.etag()
            );
            goodsDeletion.delete(festivalId, current, clock.instant());
            audit.record(
                AdminAuditAction.PRODUCT_DELETED,
                AdminAuditResourceType.GOODS,
                goodsId.toString(),
                ApiMetaSupport.resolveRequestId(request)
            );
            String json = objectMapper.writeValueAsString(new ApiResponse<>(
                new GoodsDeletedResponse(goodsId.toString(), true),
                metaSupport.unscopedMeta(request, "ko")
            ));
            return new IdempotencyResponse(HttpStatus.OK.value(), MediaType.APPLICATION_JSON_VALUE, json);
        });

        return ResponseEntity.ok(new ApiResponse<>(
            storedDeleted(execution.response()),
            metaSupport.unscopedMeta(request, "ko")
        ));
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

    static String productIdempotencyResourceId(UUID festivalId) {
        return festivalId + "/PRODUCTS";
    }

    static String productItemResourceId(UUID festivalId, UUID goodsId) {
        return festivalId + "/" + goodsId;
    }

    private AdminGoodsResponse storedGoods(IdempotencyResponse response) {
        return storedResponse(response, AdminGoodsResponse.class, "Stored product creation response is invalid");
    }

    private GoodsDeletedResponse storedDeleted(IdempotencyResponse response) {
        return storedResponse(response, GoodsDeletedResponse.class, "Stored product deletion response is invalid");
    }

    private <T> T storedResponse(IdempotencyResponse response, Class<T> type, String errorMessage) {
        try {
            String data = objectMapper.readTree(response.body()).path("data").toString();
            // HTTP input follows the current contract strictly, but a short-lived
            // persisted replay may have been written by an adjacent deployment.
            return objectMapper.readerFor(type)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(data);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(errorMessage, exception);
        }
    }

    private static ApiException invalidMediaReference() {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVALID_MEDIA_REFERENCE",
            "사용할 수 없는 상품 이미지가 포함되어 있습니다.",
            false
        );
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

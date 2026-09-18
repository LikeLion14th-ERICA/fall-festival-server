package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.domain.Notice;
import dev.espero.festival.domain.NoticeCategory;
import dev.espero.festival.domain.NoticeLinkDraft;
import dev.espero.festival.domain.NoticeTranslation;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.idempotency.CanonicalPayload;
import dev.espero.festival.idempotency.IdempotencyExecution;
import dev.espero.festival.idempotency.IdempotencyKeyPolicy;
import dev.espero.festival.idempotency.IdempotencyRequest;
import dev.espero.festival.idempotency.IdempotencyResponse;
import dev.espero.festival.persistence.NoticeStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Public and authenticated administrator notice endpoints. */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class NoticeController {

    private static final String CACHE_CONTROL = "private, no-cache, must-revalidate";
    private static final String POST_ROUTE = "/api/v2/admin/notices";
    private static final String ITEM_ROUTE = "/api/v2/admin/notices/{noticeId}";

    private final NoticeViewService views;
    private final AdminNoticeViewService adminViews;
    private final NoticeStore store;
    private final FestivalProperties properties;
    private final ApiMetaSupport metaSupport;
    private final ConditionalResponseSupport conditionalResponses;
    private final AdminMutationPreconditions mutationPreconditions;
    private final AdminIdempotencyService idempotency;
    private final AdminAuditService audit;
    private final AdminContext adminContext;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public NoticeController(
        NoticeViewService views,
        AdminNoticeViewService adminViews,
        NoticeStore store,
        FestivalProperties properties,
        ApiMetaSupport metaSupport,
        ConditionalResponseSupport conditionalResponses,
        AdminMutationPreconditions mutationPreconditions,
        AdminIdempotencyService idempotency,
        AdminAuditService audit,
        AdminContext adminContext,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.views = views;
        this.adminViews = adminViews;
        this.store = store;
        this.properties = properties;
        this.metaSupport = metaSupport;
        this.conditionalResponses = conditionalResponses;
        this.mutationPreconditions = mutationPreconditions;
        this.idempotency = idempotency;
        this.audit = audit;
        this.adminContext = adminContext;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/notices")
    public ResponseEntity<ConditionalApiResponse<NoticesResponse>> getNotices(HttpServletRequest request) {
        validatePublicQuery(request);
        NoticeViewService.NoticeListSnapshot snapshot = views.list(request);
        return conditionalResponses.respond(request, snapshot.response(), snapshot.meta(), CACHE_CONTROL);
    }

    @GetMapping("/admin/notices")
    public ResponseEntity<ApiResponse<AdminNoticesResponse>> getAdminNotices(HttpServletRequest request) {
        validateAdminQuery(request);
        AdminNoticeViewService.AdminNoticesSnapshot snapshot = adminViews.list(request);
        return ResponseEntity.ok(new ApiResponse<>(snapshot.response(), snapshot.meta()));
    }

    @GetMapping("/admin/notices/{noticeId}")
    public ResponseEntity<ConditionalApiResponse<AdminNoticeResponse>> getAdminNotice(
        HttpServletRequest request,
        @PathVariable UUID noticeId
    ) {
        validateAdminQuery(request);
        AdminNoticeViewService.AdminNoticeSnapshot snapshot = adminViews.find(request, noticeId);
        return conditionalResponses.respond(request, snapshot.response(), snapshot.meta());
    }

    @PostMapping("/admin/notices")
    public ResponseEntity<String> postAdminNotice(HttpServletRequest request, @RequestBody NoticeInput input) {
        NoticeCategory category = NoticeInputValidator.validate(input);
        AdminPrincipal principal = adminContext.requireCurrent();
        String idempotencyKey = requireIdempotencyKey(request);
        Map<String, NoticeTranslation> translations = toDomainTranslations(input.translations());
        List<NoticeLinkDraft> links = toDomainLinks(input.links());
        UUID festivalId = properties.configuredFestivalId();

        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(), "POST", POST_ROUTE, null, idempotencyKey, canonicalPayload(input)
        );

        IdempotencyExecution execution = idempotency.execute(idempotencyRequest, () -> {
            UUID noticeId = store.insert(festivalId, category, translations, links, clock.instant());
            Notice created = store.findForAdmin(festivalId, noticeId).orElseThrow();
            AdminNoticeViewService.AdminNoticeSnapshot snapshot = adminViews.snapshot(request, created);
            audit.record(
                AdminAuditAction.NOTICE_CREATED,
                AdminAuditResourceType.NOTICE,
                noticeId.toString(),
                ApiMetaSupport.resolveRequestId(request)
            );
            String json = writeJson(new ConditionalApiResponse<>(snapshot.response(), ConditionalApiMeta.from(snapshot.meta())));
            return new IdempotencyResponse(201, MediaType.APPLICATION_JSON_VALUE, json);
        });

        IdempotencyResponse response = execution.response();
        return ResponseEntity.status(response.status())
            .contentType(MediaType.APPLICATION_JSON)
            .header("Location", "/api/v2/admin/notices/" + extractId(response.body()))
            .body(response.body());
    }

    @PutMapping("/admin/notices/{noticeId}")
    @AdminMutationPolicy(AdminMutationConcurrency.IF_MATCH_REQUIRED)
    public ResponseEntity<String> putAdminNotice(
        HttpServletRequest request,
        @PathVariable UUID noticeId,
        @RequestBody NoticeInput input
    ) {
        NoticeCategory category = NoticeInputValidator.validate(input);
        AdminPrincipal principal = adminContext.requireCurrent();
        String idempotencyKey = requireIdempotencyKey(request);
        Map<String, NoticeTranslation> translations = toDomainTranslations(input.translations());
        List<NoticeLinkDraft> links = toDomainLinks(input.links());
        UUID festivalId = properties.configuredFestivalId();

        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(), "PUT", ITEM_ROUTE, noticeId.toString(), idempotencyKey, canonicalPayload(input)
        );

        IdempotencyExecution execution = idempotency.execute(idempotencyRequest, () -> {
            Notice current = store.findForUpdate(festivalId, noticeId).orElseThrow(NoticeController::notFound);
            AdminNoticeViewService.AdminNoticeSnapshot currentSnapshot = adminViews.snapshot(request, current);
            mutationPreconditions.requireCurrentRepresentation(
                request, AdminMutationConcurrency.IF_MATCH_REQUIRED, currentSnapshot.etag()
            );
            store.update(noticeId, category, translations, links, clock.instant());
            Notice updated = store.findForAdmin(festivalId, noticeId).orElseThrow();
            AdminNoticeViewService.AdminNoticeSnapshot updatedSnapshot = adminViews.snapshot(request, updated);
            audit.record(
                AdminAuditAction.NOTICE_UPDATED,
                AdminAuditResourceType.NOTICE,
                noticeId.toString(),
                ApiMetaSupport.resolveRequestId(request)
            );
            String json = writeJson(new ConditionalApiResponse<>(
                updatedSnapshot.response(), ConditionalApiMeta.from(updatedSnapshot.meta())
            ));
            return new IdempotencyResponse(200, MediaType.APPLICATION_JSON_VALUE, json);
        });

        IdempotencyResponse response = execution.response();
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    @DeleteMapping("/admin/notices/{noticeId}")
    @AdminMutationPolicy(AdminMutationConcurrency.IF_MATCH_REQUIRED)
    public ResponseEntity<String> deleteAdminNotice(HttpServletRequest request, @PathVariable UUID noticeId) {
        AdminPrincipal principal = adminContext.requireCurrent();
        String idempotencyKey = requireIdempotencyKey(request);
        UUID festivalId = properties.configuredFestivalId();

        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
            principal.adminId(), "DELETE", ITEM_ROUTE, noticeId.toString(), idempotencyKey, CanonicalPayload.empty()
        );

        IdempotencyExecution execution = idempotency.execute(idempotencyRequest, () -> {
            boolean alreadyDeleted = store.deletionStatus(festivalId, noticeId).orElseThrow(NoticeController::notFound);
            if (alreadyDeleted) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_DELETED", "이미 삭제된 공지입니다.", false);
            }
            Notice current = store.findForUpdate(festivalId, noticeId).orElseThrow(NoticeController::notFound);
            AdminNoticeViewService.AdminNoticeSnapshot currentSnapshot = adminViews.snapshot(request, current);
            mutationPreconditions.requireCurrentRepresentation(
                request, AdminMutationConcurrency.IF_MATCH_REQUIRED, currentSnapshot.etag()
            );
            store.softDelete(noticeId, clock.instant());
            audit.record(
                AdminAuditAction.NOTICE_DELETED,
                AdminAuditResourceType.NOTICE,
                noticeId.toString(),
                ApiMetaSupport.resolveRequestId(request)
            );
            ApiMeta meta = metaSupport.unscopedMeta(request, "ko");
            String json = writeJson(new ApiResponse<>(new NoticeDeletedResponse(noticeId.toString(), true), meta));
            return new IdempotencyResponse(200, MediaType.APPLICATION_JSON_VALUE, json);
        });

        IdempotencyResponse response = execution.response();
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    private Map<String, NoticeTranslation> toDomainTranslations(Map<String, NoticeInput.TranslationInput> input) {
        Map<String, NoticeTranslation> translations = new LinkedHashMap<>();
        input.forEach((locale, translation) ->
            translations.put(locale, new NoticeTranslation(translation.title(), translation.body())));
        return translations;
    }

    private List<NoticeLinkDraft> toDomainLinks(List<NoticeInput.LinkInput> input) {
        List<NoticeLinkDraft> links = new ArrayList<>(input.size());
        for (NoticeInput.LinkInput link : input) {
            links.add(new NoticeLinkDraft(link.url(), link.labels()));
        }
        return links;
    }

    private CanonicalPayload canonicalPayload(NoticeInput input) {
        Map<String, Object> translations = new LinkedHashMap<>();
        input.translations().forEach((locale, translation) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("title", translation.title());
            fields.put("body", translation.body());
            translations.put(locale, fields);
        });
        List<Map<String, Object>> links = new ArrayList<>();
        for (NoticeInput.LinkInput link : input.links()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("url", link.url());
            fields.put("labels", new LinkedHashMap<>(link.labels()));
            links.add(fields);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", input.type());
        payload.put("translations", translations);
        payload.put("links", links);
        return CanonicalPayload.from(payload);
    }

    private String requireIdempotencyKey(HttpServletRequest request) {
        List<String> keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다.", false
            );
        }
        if (keys.size() != 1 || !IdempotencyKeyPolicy.isValid(keys.getFirst())) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key 헤더 형식이 올바르지 않습니다.", false
            );
        }
        return keys.getFirst();
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private String extractId(String json) {
        return objectMapper.readTree(json).path("data").path("id").asString();
    }

    private void validatePublicQuery(HttpServletRequest request) {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
    }

    private void validateAdminQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.", false);
    }
}
